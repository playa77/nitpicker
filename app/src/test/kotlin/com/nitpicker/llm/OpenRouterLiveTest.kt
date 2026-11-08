package com.nitpicker.llm

import com.nitpicker.domain.*
import com.nitpicker.prompts.Prompts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt in explicitly: NITPICKER_LIVE_OPENROUTER=1, with OPENROUTER_API_KEY injected. This exercises
 * the production gateway/DTO contracts. The small speech fixture is
 * synthesized locally, contains no meeting data.
 */
class OpenRouterLiveTest {
    private fun gateway(): OpenRouterGateway {
        assumeTrue("Live inference is opt-in", System.getenv("NITPICKER_LIVE_OPENROUTER") == "1")
        val key = System.getenv("OPENROUTER_API_KEY")
        assumeTrue("Test API key required", !key.isNullOrBlank())
        return OpenRouterGateway(
            GatewayConfig(apiKeyProvider = { key!! }, retry = RetryPolicy(maxAttempts = 1))
        )
    }

    private suspend fun asr(model: String, language: Language = Language.EN) {
        val gateway = gateway()
        val audio = javaClass.getResourceAsStream("/audio/meeting-${language.name.lowercase()}.wav")!!.use { it.readBytes() }
        val started = System.nanoTime()
        val response =
            gateway.postTranscription(
                TranscriptionRequest(model, audio, language, Prompts.asrInstruction(language))
            )
        println("ASR live: $model $language ${(System.nanoTime() - started) / 1_000_000}ms")
        val transcript = response.text
        assertTrue(
            "ASR must return the known speech fixture",
            transcript.contains(if (language == Language.EN) "meeting" else "Besprechung", ignoreCase = true) &&
                transcript.contains("budget", ignoreCase = true),
        )
    }

    @Test fun voxtralEnglishAudioContract() = runBlocking { asr(VOXTRAL_ASR_MODEL) }

    @Test fun voxtralGermanAudioContract() = runBlocking { asr(VOXTRAL_ASR_MODEL, Language.DE) }

    @Test fun maiAudioContract() = runBlocking { asr(DEFAULT_ASR_MODEL) }

    @Test fun maiGermanAudioContract() = runBlocking { asr(DEFAULT_ASR_MODEL, Language.DE) }

    @Test
    fun geminiSegmentationAndAnalysisContracts() = runBlocking {
        val gateway = gateway()
        val user = buildJsonObject {
            put("transcript", "The meeting starts at nine. The budget is four million euros.")
            put("previousStatementTail", "")
            put("recentSpeakerTags", buildJsonArray {})
            put("language", "EN")
        }
        val segment =
            gateway.postChat(
                ChatRequest(
                    ANALYSIS_MODEL,
                    listOf(
                        ChatMessage(
                            "system",
                            JsonPrimitive(Prompts.segmentationSystem(Language.EN)),
                        ),
                        ChatMessage("user", JsonPrimitive(user.toString())),
                    ),
                )
            )
        val payload =
            LlmJsonParser.parse<SegmentationPayload>(segment.choices.first().message.content!!)
        assertTrue(payload.statements.isNotEmpty())
        val context = buildJsonObject {
            put(
                "statements",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("seq", 1)
                            put("text", "Berlin is the capital of France.")
                        }
                    )
                },
            )
            put("recentWindow", buildJsonArray {})
            put("claimsDigest", buildJsonArray {})
            put("language", "EN")
        }
        val analysis =
            gateway.postChat(
                ChatRequest(
                    ANALYSIS_MODEL,
                    listOf(
                        ChatMessage("system", JsonPrimitive(Prompts.analysisSystem(Language.EN))),
                        ChatMessage("user", JsonPrimitive(context.toString())),
                    ),
                )
            )
        val result =
            LlmJsonParser.parse<AnalysisPayload>(analysis.choices.first().message.content!!)
        assertTrue(result.annotations.any { it.family == "FACT_CHECK" && it.statementSeq == 1L })
    }
}
