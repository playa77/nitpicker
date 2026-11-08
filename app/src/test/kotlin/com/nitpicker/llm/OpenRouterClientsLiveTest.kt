package com.nitpicker.llm

import com.nitpicker.harness.PipelineHarness
import com.nitpicker.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real client chain with synthetic speech only; the runtime key is never persisted. */
@RunWith(RobolectricTestRunner::class)
class OpenRouterClientsLiveTest {
    private suspend fun checkModel(model: String) {
        assumeTrue(System.getenv("NITPICKER_LIVE_OPENROUTER") == "1")
        val key = System.getenv("OPENROUTER_API_KEY")
        assumeTrue(!key.isNullOrBlank())
        val h = PipelineHarness()
        try {
            h.session()
            val gateway = OpenRouterGateway(GatewayConfig(apiKeyProvider = { key!! }, retry = RetryPolicy(maxAttempts = 1)))
            val audio = javaClass.getResourceAsStream("/audio/meeting-en.wav")!!.use { it.readBytes() }
            // Persist the exact upload bytes through the production capture-to-repository seam.
            h.repo.enqueueChunk(h.id, AudioChunkData(h.id, 0, audio, startedAtOffsetMs = 0, durationMs = 3656))
            val row = h.repo.claimNextChunkForAsr(h.id)!!
            val transcript = AsrClient(gateway, h.settings).transcribe(row, Language.EN, model)
            assertTrue(transcript.text.contains("budget", true))
            h.repo.saveTranscript(transcript)
            val segmentationRow = h.repo.claimNextChunkForSegmentation(h.id)!!
            val segmented = StatementProcessor(gateway).segment(segmentationRow, null, emptyList(), Language.EN)
            assertTrue(segmented.statements.isNotEmpty())
            h.repo.applySegmentation(row.id, h.id, segmented)
            val ctx = h.repo.claimNextAnalysisJob(h.id)!!
            h.repo.saveAnalysis(ctx.job.id, h.id, AnalysisEngine(gateway).analyze(ctx))
            assertEquals(ChunkState.ANALYZED, h.repo.sessionForExport(h.id).chunks.single().state)
        } finally { h.close() }
    }
    @Test fun whisperThroughProductionClients() = runBlocking(Dispatchers.IO) { checkModel("openai/whisper-large-v3-turbo") }
    @Test fun maiThroughProductionClients() = runBlocking(Dispatchers.IO) { checkModel("microsoft/mai-transcribe-2") }
}
