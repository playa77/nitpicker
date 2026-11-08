package com.nitpicker.llm

import com.nitpicker.data.db.AudioChunkRow
import com.nitpicker.data.settings.SettingsStore
import com.nitpicker.domain.*
import com.nitpicker.prompts.Prompts

class AsrClient(private val transport: ChatTransport, private val settings: SettingsStore) {
    suspend fun transcribe(chunk: AudioChunkRow, language: Language = Language.DE,
                           model: String = settings.snapshot().asrModel): TranscriptResult {
        val text = transport.postTranscription(TranscriptionRequest(model,
            requireNotNull(chunk.audioBlob), language, Prompts.asrInstruction(language))).text
        if (text.isBlank()) throw MalformedLlmOutputException("empty transcript")
        return TranscriptResult(chunk.id, text)
    }
}
