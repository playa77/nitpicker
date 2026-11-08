package com.nitpicker.fakes

import com.nitpicker.domain.*
import com.nitpicker.llm.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class TestDoublesTest {
    @Test
    fun sequentialResponsesErrorsAndRequestSnapshots() = runBlocking {
        val transport = ScriptedChatTransport()
        val response = ChatResponse(listOf(Choice(RespMessage("first"))))
        val error = OpenRouterAuthException(401)
        transport.enqueue(response)
        transport.enqueueError(error)
        val first = ChatRequest("one", listOf(ChatMessage("user", JsonPrimitive("hello"))))
        val second = first.copy(model = "two")
        assertEquals(response, transport.postChat(first))
        val snapshot = transport.requests
        try {
            transport.postChat(second)
            fail()
        } catch (e: OpenRouterAuthException) {
            assertSame(error, e)
        }
        assertEquals(1, snapshot.size)
        assertEquals(listOf(first, second), transport.requests)
        assertEquals(2, transport.callCount)
        assertEquals(0, transport.remaining)
        try {
            transport.postChat(first)
            fail()
        } catch (_: IllegalStateException) {}
        assertEquals(3, transport.callCount)
    }

    @Test
    fun fakeGatePublishesManualTransitions() {
        val gate = FakeNetworkGate()
        assertEquals(NetworkState.ONLINE, gate.state.value)
        gate.setState(NetworkState.OFFLINE)
        assertEquals(NetworkState.OFFLINE, gate.state.value)
        gate.setState(NetworkState.ONLINE)
        assertEquals(NetworkState.ONLINE, gate.state.value)
    }

    @Test
    fun mixedTransportScriptRecordsCrossEndpointOrder() = runBlocking {
        val transport = ScriptedChatTransport()
        val audio = TranscriptionRequest("asr", byteArrayOf(1, 2), Language.DE)
        val chat = ChatRequest("analysis", emptyList())
        transport.enqueue(TranscriptionResponse("speech"))
        transport.enqueue(ChatResponse(listOf(Choice(RespMessage("analysis")))))
        val error = OpenRouterHttpException(429)
        transport.enqueueError(error)
        assertEquals("speech", transport.postTranscription(audio).text)
        assertEquals("analysis", transport.postChat(chat).choices.single().message.content)
        try {
            transport.postTranscription(audio)
            fail()
        } catch (e: OpenRouterHttpException) {
            assertSame(error, e)
        }
        assertEquals(
            listOf(
                TransportCall.Transcription(audio),
                TransportCall.Chat(chat),
                TransportCall.Transcription(audio),
            ),
            transport.calls,
        )
        assertEquals(2, transport.transcriptionRequests.size)
        assertEquals(1, transport.requests.size)
        assertEquals(3, transport.callCount)
    }
}
