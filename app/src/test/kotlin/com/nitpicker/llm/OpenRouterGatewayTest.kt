package com.nitpicker.llm

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*

class OpenRouterGatewayTest {
    private lateinit var server: MockWebServer
    private val sleeps = mutableListOf<Long>()
    private val req = ChatRequest("test-model", listOf(ChatMessage("user", JsonPrimitive("test"))))
    private val valid =
        """{"choices":[{"message":{"role":"assistant","content":"ok"}}],"usage":{"total_tokens":7},"id":"ignored"}"""

    @Before
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun close() {
        server.shutdown()
    }

    private fun gateway(
        retry: RetryPolicy = RetryPolicy(),
        timeouts: HttpTimeouts = HttpTimeouts(),
    ) =
        OpenRouterGateway(
            GatewayConfig(
                server.url("/api/v1").newBuilder().host("127.0.0.1").build().toString(),
                { "dummy-test-key" },
                retry,
                timeouts,
            ),
            sleep = { sleeps.add(it) },
            random = { 0.5 },
            nowMs = { 0 },
        )

    @Test
    fun validResponseAndAttributionAndWirePayload() = runBlocking {
        server.enqueue(MockResponse().setBody(valid))
        val response = gateway().postChat(req)
        assertEquals("ok", response.choices.single().message.content)
        assertEquals(7L, response.usage!!.total_tokens)
        val sent = server.takeRequest()
        assertEquals("/api/v1/chat/completions", sent.path)
        assertEquals("Bearer dummy-test-key", sent.getHeader("Authorization"))
        assertEquals("Nitpicker", sent.getHeader("X-Title"))
        assertEquals(req, LlmJsonParser.json.decodeFromString<ChatRequest>(sent.body.readUtf8()))
    }

    @Test
    fun rateLimitHonorsRetryAfterAndCapsIt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "2"))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "999"))
        server.enqueue(MockResponse().setBody(valid))
        gateway().postChat(req)
        assertEquals(listOf(2000L, 60000L), sleeps)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun retryAfterHttpDateAndInvalidHeaderFallback() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setHeader("Retry-After", "Thu, 01 Jan 1970 00:00:03 GMT")
        )
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "invalid"))
        server.enqueue(MockResponse().setBody(valid))
        gateway().postChat(req)
        assertEquals(listOf(3000L, 8000L), sleeps)
    }

    @Test
    fun serverFailureRetriesAndExhaustionHasTypedStatus() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(valid))
        gateway().postChat(req)
        assertEquals(listOf(2000L), sleeps)
        repeat(3) { server.enqueue(MockResponse().setResponseCode(503)) }
        try {
            gateway().postChat(req)
            fail()
        } catch (e: OpenRouterHttpException) {
            assertEquals(503, e.status)
        }
        assertEquals(5, server.requestCount)
    }

    @Test
    fun authAndOtherClientErrorsNeverRetry() = runBlocking {
        for (code in listOf(401, 403, 400, 302)) {
            server.enqueue(
                MockResponse()
                    .setResponseCode(code)
                    .setHeader("Location", "https://example.invalid")
            )
            try {
                gateway().postChat(req)
                fail()
            } catch (e: java.io.IOException) {
                if (code == 401 || code == 403) assertTrue(e is OpenRouterAuthException)
                else assertTrue(e is OpenRouterHttpException)
            }
        }
        assertEquals(4, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun malformedSuccessDoesNotRetry() = runBlocking {
        server.enqueue(MockResponse().setBody("not json"))
        try {
            gateway().postChat(req)
            fail()
        } catch (_: MalformedLlmOutputException) {}
        assertEquals(1, server.requestCount)
    }

    @Test
    fun readTimeoutIsTypedAndObeysAttemptBudget() = runBlocking {
        repeat(2) {
            server.enqueue(MockResponse().setBody(valid).setBodyDelay(500, TimeUnit.MILLISECONDS))
        }
        try {
            gateway(RetryPolicy(maxAttempts = 2), HttpTimeouts(readMs = 100)).postChat(req)
            fail()
        } catch (_: OpenRouterTimeoutException) {}
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals(listOf(2000L), sleeps)
    }

    @Test
    fun cancellationStopsAnOutstandingCall() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val task = launch(Dispatchers.Default) { gateway().postChat(req) }
        withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)) }
        task.cancelAndJoin()
        assertTrue(task.isCancelled)
        assertTrue(sleeps.isEmpty())
    }

    @Test
    fun transcriptionSendsExactWavAndLanguageAsMultipartForBothModels() = runBlocking {
        val wav = byteArrayOf(82, 73, 70, 70, 0, 0, 0, 0, 87, 65, 86, 69, 0, -1)
        for (model in listOf("openai/whisper-large-v3-turbo", "microsoft/mai-transcribe-2")) {
            server.enqueue(
                MockResponse().setBody("""{"text":"Guten Morgen","provider_metadata":true}""")
            )
            val result =
                gateway()
                    .postTranscription(
                        TranscriptionRequest(
                            model,
                            wav,
                            com.nitpicker.domain.Language.DE,
                            "Transcribe verbatim",
                        )
                    )
            assertEquals("Guten Morgen", result.text)
            val sent = server.takeRequest()
            assertEquals("/api/v1/audio/transcriptions", sent.path)
            assertEquals("Bearer dummy-test-key", sent.getHeader("Authorization"))
            val contentType = sent.getHeader("Content-Type")!!
            assertTrue(contentType.startsWith("multipart/form-data; boundary="))
            val boundary = contentType.substringAfter("boundary=")
            val parts = sent.body.readByteArray().toString(Charsets.ISO_8859_1).split("--$boundary")
            assertTrue(parts.any { it.contains("name=\"model\"") && it.endsWith("$model\r\n") })
            assertTrue(parts.any { it.contains("name=\"language\"") && it.endsWith("de\r\n") })
            assertTrue(
                parts.any {
                    it.contains("name=\"prompt\"") && it.endsWith("Transcribe verbatim\r\n")
                }
            )
            val file = parts.single { it.contains("name=\"file\"") }
            assertTrue(file.contains("filename=\"chunk.wav\""))
            assertTrue(file.contains("Content-Type: audio/wav"))
            assertArrayEquals(
                wav,
                file
                    .substringAfter("\r\n\r\n")
                    .removeSuffix("\r\n")
                    .toByteArray(Charsets.ISO_8859_1),
            )
        }
    }

    @Test
    fun transcriptionRetriesPreserveAudioAndHonorSameBudget() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "1"))
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("""{"text":"ok"}"""))
        val req =
            TranscriptionRequest(
                "microsoft/mai-transcribe-2",
                byteArrayOf(0, -1, 2),
                com.nitpicker.domain.Language.EN,
            )
        assertEquals("ok", gateway().postTranscription(req).text)
        assertEquals(listOf(1000L, 8000L), sleeps)
        val bodies = (1..3).map { server.takeRequest().body.readByteArray() }
        assertArrayEquals(bodies[0], bodies[1])
        assertArrayEquals(bodies[0], bodies[2])
        assertFalse(bodies[0].toString(Charsets.ISO_8859_1).contains("name=\"prompt\""))
    }

    @Test
    fun transcriptionAuthRoutingAndMalformedOutputDoNotRetry() = runBlocking {
        val req = TranscriptionRequest("test", byteArrayOf(1), com.nitpicker.domain.Language.EN)
        server.enqueue(MockResponse().setResponseCode(401))
        try {
            gateway().postTranscription(req)
            fail()
        } catch (_: OpenRouterAuthException) {}
        server.enqueue(MockResponse().setResponseCode(404))
        try {
            gateway().postTranscription(req)
            fail()
        } catch (e: OpenRouterHttpException) {
            assertEquals(404, e.status)
        }
        server.enqueue(MockResponse().setBody("{}"))
        try {
            gateway().postTranscription(req)
            fail()
        } catch (_: MalformedLlmOutputException) {}
        assertEquals(3, server.requestCount)
        assertTrue(sleeps.isEmpty())
    }
}
