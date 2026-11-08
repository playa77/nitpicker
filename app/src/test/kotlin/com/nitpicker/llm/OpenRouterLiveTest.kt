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
 * only WP-07's gateway/DTO contracts; clients arrive in WP-10. The small speech fixture is
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

    private suspend fun asr(model: String) {
        val gateway = gateway()
        val audio = javaClass.getResourceAsStream("/audio/meeting-en.wav")!!.use { it.readBytes() }
        val response =
            gateway.postTranscription(
                TranscriptionRequest(model, audio, Language.EN, Prompts.asrInstruction(Language.EN))
            )
        val transcript = response.text
        assertTrue(
            "ASR must return the known speech fixture",
            transcript.contains("meeting", ignoreCase = true) &&
                transcript.contains("budget", ignoreCase = true),
        )
    }

    @Test fun whisperAudioContract() = runBlocking { asr("openai/whisper-large-v3-turbo") }

    @Test fun maiAudioContract() = runBlocking { asr("microsoft/mai-transcribe-2") }

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
