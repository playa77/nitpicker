package com.nitpicker.llm

import com.nitpicker.domain.OPENROUTER_BASE_URL
import java.io.IOException

interface ChatTransport {
    suspend fun postChat(req: ChatRequest): ChatResponse

    suspend fun postTranscription(req: TranscriptionRequest): TranscriptionResponse
}

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val backoffMs: List<Long> = listOf(2000, 8000, 32000),
    val jitterFraction: Double = 0.2,
) {
    init {
        require(
            maxAttempts > 0 &&
                backoffMs.isNotEmpty() &&
                backoffMs.all { it >= 0 } &&
                jitterFraction in 0.0..1.0
        )
    }
}

data class HttpTimeouts(
    val connectMs: Long = 10000,
    val writeMs: Long = 30000,
    val readMs: Long = 120000,
) {
    init {
        require(connectMs > 0 && writeMs > 0 && readMs > 0)
    }
}

data class GatewayConfig(
    val baseUrl: String = OPENROUTER_BASE_URL,
    val apiKeyProvider: () -> String,
    val retry: RetryPolicy = RetryPolicy(),
    val timeouts: HttpTimeouts = HttpTimeouts(),
)

class OpenRouterHttpException(val status: Int, cause: Throwable? = null) :
    IOException("OpenRouter HTTP $status", cause)

class OpenRouterTimeoutException(cause: Throwable? = null) :
    IOException("OpenRouter transport failed", cause)

class OpenRouterAuthException(val status: Int) :
    IOException("OpenRouter authentication failed ($status)")

class MalformedLlmOutputException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
