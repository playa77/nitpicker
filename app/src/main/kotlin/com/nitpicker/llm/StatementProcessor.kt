package com.nitpicker.llm

import com.nitpicker.data.db.AudioChunkRow
import com.nitpicker.domain.*
import com.nitpicker.prompts.Prompts
import kotlinx.serialization.json.*

internal suspend inline fun <reified T> ChatTransport.structured(system: String, user: JsonObject): T {
    var messages = listOf(ChatMessage("system", JsonPrimitive(system)),
        ChatMessage("user", JsonPrimitive(user.toString())))
    repeat(2) { attempt ->
        val content = postChat(ChatRequest(ANALYSIS_MODEL, messages)).choices.firstOrNull()?.message?.content.orEmpty()
        try { return LlmJsonParser.parse<T>(content) }
        catch (e: MalformedLlmOutputException) {
            if (attempt == 1) throw e
            messages = messages + ChatMessage("assistant", JsonPrimitive(content)) +
                ChatMessage("user", JsonPrimitive("Your previous response was not valid JSON matching the schema. Return only corrected JSON."))
        }
    }
    error("unreachable")
}

class StatementProcessor(private val transport: ChatTransport) {
    suspend fun segment(chunk: AudioChunkRow, tail: String?, recentTags: List<String>, language: Language): SegmentationResult {
        val user = buildJsonObject {
            put("transcript", chunk.transcript.orEmpty())
            put("previousStatementTail", tail.orEmpty())
            put("recentSpeakerTags", JsonArray(recentTags.map(::JsonPrimitive)))
            put("language", language.name)
        }
        val payload = transport.structured<SegmentationPayload>(Prompts.segmentationSystem(language), user)
        return SegmentationResult(chunk.id, payload.statements)
    }
}
