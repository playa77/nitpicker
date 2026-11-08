package com.nitpicker.fakes

import com.nitpicker.llm.*
import kotlinx.coroutines.flow.first

sealed interface TransportCall {
    data class Chat(val request: ChatRequest) : TransportCall

    data class Transcription(val request: TranscriptionRequest) : TransportCall
}

/** One shared response/error script preserves order across both HTTP operations. */
class ScriptedChatTransport(script: List<Result<Any>> = emptyList(), private val awaitTurn: Boolean = false) : ChatTransport {
    private val revision = kotlinx.coroutines.flow.MutableStateFlow(0)
    private suspend fun awaitOperation(audio: Boolean, analysis: Boolean = false) {
        if (!awaitTurn) return
        revision.first {
            synchronized(lock) {
                val next = outcomes.firstOrNull()?.getOrNull()
                next == null || (audio && next is TranscriptionResponse) || (!audio && next is ChatResponse &&
                    (next.choices.firstOrNull()?.message?.content?.contains("\"annotations\"") == analysis))
            }
        }
    }
    private val lock = Any()
    private val outcomes = ArrayDeque(script)
    private val recorded = mutableListOf<TransportCall>()
    val calls: List<TransportCall>
        get() = synchronized(lock) { recorded.toList() }

    val requests: List<ChatRequest>
        get() =
            synchronized(lock) {
                recorded.filterIsInstance<TransportCall.Chat>().map { it.request }
            }

    val transcriptionRequests: List<TranscriptionRequest>
        get() =
            synchronized(lock) {
                recorded.filterIsInstance<TransportCall.Transcription>().map { it.request }
            }

    val callCount: Int
        get() = synchronized(lock) { recorded.size }

    val remaining: Int
        get() = synchronized(lock) { outcomes.size }

    fun enqueue(response: ChatResponse) {
        synchronized(lock) { outcomes.addLast(Result.success(response)) }
    }

    fun enqueue(response: TranscriptionResponse) {
        synchronized(lock) { outcomes.addLast(Result.success(response)) }
    }

    fun enqueueError(error: Throwable) {
        synchronized(lock) { outcomes.addLast(Result.failure(error)) }
    }

    private fun take(call: TransportCall): Any {
        recorded.add(call)
        check(outcomes.isNotEmpty()) { "No scripted response for call ${recorded.size}" }
        val result = outcomes.removeFirst()
        revision.value += 1
        return result.getOrThrow()
    }

    override suspend fun postChat(req: ChatRequest): ChatResponse {
        awaitOperation(false, req.messages.firstOrNull()?.content.toString().contains("You analyze"))
        return synchronized(lock) {
            take(TransportCall.Chat(req)) as? ChatResponse
                ?: error("Expected a scripted chat response")
        }

    }

    override suspend fun postTranscription(req: TranscriptionRequest): TranscriptionResponse {
        awaitOperation(true)
        return synchronized(lock) {
            take(TransportCall.Transcription(req)) as? TranscriptionResponse
                ?: error("Expected a scripted transcription response")
        }
    }
}
