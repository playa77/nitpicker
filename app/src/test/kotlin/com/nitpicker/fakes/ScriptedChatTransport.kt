package com.nitpicker.fakes

import com.nitpicker.llm.*

sealed interface TransportCall {
    data class Chat(val request: ChatRequest) : TransportCall

    data class Transcription(val request: TranscriptionRequest) : TransportCall
}

/** One shared response/error script preserves order across both HTTP operations. */
class ScriptedChatTransport(script: List<Result<Any>> = emptyList()) : ChatTransport {
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
        return outcomes.removeFirst().getOrThrow()
    }

    override suspend fun postChat(req: ChatRequest): ChatResponse =
        synchronized(lock) {
            take(TransportCall.Chat(req)) as? ChatResponse
                ?: error("Expected a scripted chat response")
        }

    override suspend fun postTranscription(req: TranscriptionRequest): TranscriptionResponse =
        synchronized(lock) {
            take(TransportCall.Transcription(req)) as? TranscriptionResponse
                ?: error("Expected a scripted transcription response")
        }
}
