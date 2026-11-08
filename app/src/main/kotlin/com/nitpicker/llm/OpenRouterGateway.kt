package com.nitpicker.llm

import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class OpenRouterGateway(
    private val cfg: GatewayConfig,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val random: () -> Double = { Random.nextDouble() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) : ChatTransport {
    private val client =
        OkHttpClient.Builder()
            .connectTimeout(cfg.timeouts.connectMs, TimeUnit.MILLISECONDS)
            .writeTimeout(cfg.timeouts.writeMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.timeouts.readMs, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

    override suspend fun postChat(req: ChatRequest): ChatResponse =
        withContext(Dispatchers.IO) {
            post(
                "/chat/completions",
                LlmJsonParser.json
                    .encodeToString(req)
                    .toRequestBody("application/json".toMediaType()),
            ) {
                LlmJsonParser.json.decodeFromString<ChatResponse>(it)
            }
        }

    override suspend fun postTranscription(req: TranscriptionRequest): TranscriptionResponse =
        withContext(Dispatchers.IO) {
            require(req.model.isNotBlank() && req.audioWav.isNotEmpty())
            val body =
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("model", req.model)
                    .addFormDataPart("language", req.language.name.lowercase(java.util.Locale.ROOT))
                    .addFormDataPart(
                        "file",
                        "chunk.wav",
                        req.audioWav.toRequestBody("audio/wav".toMediaType()),
                    )
                    .apply { req.prompt?.let { addFormDataPart("prompt", it) } }
                    .build()
            post("/audio/transcriptions", body) {
                LlmJsonParser.json.decodeFromString<TranscriptionResponse>(it)
            }
        }

    private suspend fun <T> post(path: String, payload: RequestBody, decode: (String) -> T): T {
        for (attempt in 0 until cfg.retry.maxAttempts) {
            val request =
                Request.Builder()
                    .url(cfg.baseUrl.trimEnd('/') + path)
                    .header("Authorization", "Bearer ${cfg.apiKeyProvider()}")
                    .header("HTTP-Referer", "nitpicker.local")
                    .header("X-Title", "Nitpicker")
                    .post(payload)
                    .build()
            var retryAfter: Long? = null
            val failure: IOException =
                try {
                    execute(request).use { response ->
                        when {
                            response.code == 401 || response.code == 403 ->
                                throw OpenRouterAuthException(response.code)
                            response.isSuccessful -> {
                                val body =
                                    response.body?.string()
                                        ?: throw MalformedLlmOutputException("Empty HTTP body")
                                return try {
                                    decode(body)
                                } catch (e: SerializationException) {
                                    throw MalformedLlmOutputException(
                                        "Invalid OpenRouter response",
                                        e,
                                    )
                                }
                            }
                            response.code == 429 || response.code in 500..599 -> {
                                if (response.code == 429)
                                    retryAfter = parseRetryAfter(response.header("Retry-After"))
                                OpenRouterHttpException(response.code)
                            }
                            else -> throw OpenRouterHttpException(response.code)
                        }
                    }
                } catch (e: OpenRouterAuthException) {
                    throw e
                } catch (e: OpenRouterHttpException) {
                    throw e
                } catch (e: IOException) {
                    OpenRouterTimeoutException(e)
                }
            if (attempt == cfg.retry.maxAttempts - 1) throw failure
            val base = cfg.retry.backoffMs[attempt.coerceAtMost(cfg.retry.backoffMs.lastIndex)]
            sleep(
                retryAfter
                    ?: (base *
                            (1 - cfg.retry.jitterFraction +
                                2 * cfg.retry.jitterFraction * random()))
                        .toLong()
            )
        }
        error("Unreachable retry policy")
    }

    private fun parseRetryAfter(value: String?): Long? {
        if (value == null) return null
        val seconds = value.trim().toLongOrNull()
        if (seconds != null) return seconds.coerceIn(0, 60) * 1000
        return runCatching {
            (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli() - nowMs())
                .coerceIn(0, 60000)
        }
            .getOrNull()
    }

    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        continuation.resume(response) { _, value, _ -> value.close() }
                    }
                }
            )
        }
}
