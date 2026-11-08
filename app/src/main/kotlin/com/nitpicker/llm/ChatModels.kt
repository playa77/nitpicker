package com.nitpicker.llm

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable data class ChatRequest(val model: String, val messages: List<ChatMessage>)

@Serializable data class ChatMessage(val role: String, val content: JsonElement)

@Serializable data class TextPart(val type: String = "text", val text: String)

@Serializable data class AudioPart(val type: String = "input_audio", val input_audio: AudioData)

@Serializable data class AudioData(val data: String, val format: String = "wav")

@Serializable data class ChatResponse(val choices: List<Choice>, val usage: Usage? = null)

@Serializable data class Choice(val message: RespMessage)

@Serializable data class RespMessage(val content: String? = null)

@Serializable data class Usage(val total_tokens: Long? = null)

@Serializable data class SegmentationPayload(val statements: List<SegmentedStatement>)

@Serializable
data class SegmentedStatement(
    val text: String,
    val continuesPrevious: Boolean,
    val speakerTag: String?,
    val endsProvisionally: Boolean,
)

@Serializable
data class AnalysisPayload(val annotations: List<AnnotationSpec>, val claims: List<String>)

@Serializable
data class AnnotationSpec(
    val statementSeq: Long,
    val family: String,
    val subType: String,
    val note: String,
    val quote: String? = null,
)

/** Binary WAV upload; never base64-encoded or embedded in a chat message. */
data class TranscriptionRequest(
    val model: String,
    val audioWav: ByteArray,
    val language: com.nitpicker.domain.Language,
    val prompt: String? = null,
)

@Serializable data class TranscriptionResponse(val text: String)
