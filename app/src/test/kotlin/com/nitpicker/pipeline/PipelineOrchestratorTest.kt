package com.nitpicker.pipeline

import com.nitpicker.harness.PipelineHarness
import com.nitpicker.llm.*
import com.nitpicker.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PipelineOrchestratorTest {
    private lateinit var h: PipelineHarness
    @Before fun setup() { h = PipelineHarness() }
    @After fun close() = runBlocking { h.close() }
    @Test fun retryAsrThenSuccessPreservesFifo() = runBlocking(Dispatchers.IO) {
        h.session(); h.transport.enqueueError(OpenRouterHttpException(500)); h.script(); h.start()
        h.pcmChunk(); h.drain()
        assertEquals(2, h.transport.transcriptionRequests.size)
        assertArrayEquals(h.transport.transcriptionRequests[0].audioWav, h.transport.transcriptionRequests[1].audioWav)
        assertEquals(1, h.metrics.snapshot.value.asrFailures)
    }
    @Test fun authenticationParksUntilCredentialReplacement() = runBlocking(Dispatchers.IO) {
        h.session(); h.transport.enqueueError(OpenRouterAuthException(401)); h.script(); h.start(); h.pcmChunk()
        h.await { h.metrics.snapshot.value.asrFailures == 1 }
        delay(150); assertEquals(1, h.transport.callCount)
        h.settings.setApiKey("synthetic-test-key")
        h.drain(); assertEquals(ChunkState.DISCARDED, h.repo.sessionForExport(h.id).chunks.single().state)
    }
    @Test fun segmentationTerminalFailureCreatesGapAndRetainsAudio() = runBlocking(Dispatchers.IO) {
        h.session(2)
        h.transport.enqueue(TranscriptionResponse("Speech."))
        repeat(2) { h.transport.enqueue(PipelineHarness.chat("invalid")) }
        h.start(); h.pcmChunk(); h.complete()
        assertEquals(ChunkState.FAILED, h.repo.sessionForExport(h.id).chunks.single().state)
        assertEquals(1, h.retention.filesForSession(h.id).size)
        assertTrue(h.sink.files.values.single().contains("transcription gap"))
    }
    @Test fun crashBetweenRetainAndDiscardIsIdempotent() = runBlocking(Dispatchers.IO) {
        h.session(2); h.pcmChunk()
        val row = h.repo.claimNextChunkForAsr(h.id)!!
        h.repo.markChunkFailed(row.id, "test interruption")
        h.retention.retainIfNeeded(h.id, row, row.audioBlob!!, h.settings.snapshot())
        // Simulate death after the durable file write, before nullBlob.
        h.restart(); h.drain()
        assertEquals(1, h.retention.filesForSession(h.id).size)
        assertNull(h.repo.loadBlob(row.id))
        assertArrayEquals(row.audioBlob, h.retention.filesForSession(h.id).single().readBytes())
    }
    @Test fun emptySessionCanDrainWhileOffline() = runBlocking(Dispatchers.IO) {
        h.session(); h.gate.setState(NetworkState.OFFLINE); h.start(); h.complete()
        assertEquals(SessionStatus.COMPLETED, h.repo.sessionForExport(h.id).session.status)
        assertEquals(0, h.transport.callCount)
    }
    @Test fun recoveryNeverDispatchesRemovedModel() = runBlocking(Dispatchers.IO) {
        h.session(asrModel = "openai/whisper-large-v3-turbo")
        h.script(); h.pcmChunk(); h.start(); h.drain()
        assertEquals(DEFAULT_ASR_MODEL, h.transport.transcriptionRequests.single().model)
        assertEquals(DEFAULT_ASR_MODEL, h.repo.sessionMetadata(h.id).asrModel)
    }

    @Test fun recoveryPreservesVoxtralSelection() = runBlocking(Dispatchers.IO) {
        h.session(asrModel = VOXTRAL_ASR_MODEL)
        h.script(); h.pcmChunk(); h.start(); h.drain()
        assertEquals(VOXTRAL_ASR_MODEL, h.transport.transcriptionRequests.single().model)
    }
    @Test fun recoveryDoesNotRewriteHistoricalCompletedSession() = runBlocking(Dispatchers.IO) {
        val oldModel = "openai/whisper-large-v3-turbo"
        h.session(asrModel = oldModel)
        h.repo.setStatus(h.id, SessionStatus.COMPLETED)
        h.repo.recoverInFlight(h.id)
        assertEquals(oldModel, h.repo.sessionMetadata(h.id).asrModel)
    }
}
