package com.nitpicker.llm

import com.nitpicker.harness.PipelineHarness
import com.nitpicker.domain.*
import com.nitpicker.fakes.ScriptedChatTransport
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ClientsTest {
    private lateinit var h: PipelineHarness
    @Before fun setup() { h = PipelineHarness() }
    @After fun close() = runBlocking { h.close() }
    @Test fun segmentationRepairsOnceWithOriginalContext() = runBlocking(Dispatchers.IO) {
        h.session(); h.pcmChunk()
        val row = h.repo.sessionForExport(h.id).chunks.single()
        val t = ScriptedChatTransport()
        t.enqueue(PipelineHarness.chat("broken"))
        t.enqueue(PipelineHarness.chat("""{"statements":[]}"""))
        assertTrue(StatementProcessor(t).segment(row, "tail", listOf("S2"), Language.EN).statements.isEmpty())
        assertEquals(2, t.callCount)
        assertEquals(4, t.requests.last().messages.size)
        assertTrue(t.requests.first().messages.last().content.toString().contains("tail"))
    }
    @Test fun secondMalformedResponseIsTerminal() = runBlocking(Dispatchers.IO) {
        h.session(); h.pcmChunk()
        val t = ScriptedChatTransport()
        repeat(2) { t.enqueue(PipelineHarness.chat("broken")) }
        try {
            StatementProcessor(t).segment(h.repo.sessionForExport(h.id).chunks.single(), null, emptyList(), Language.DE)
            fail("Expected malformed output")
        } catch (_: MalformedLlmOutputException) { assertEquals(2, t.callCount) }
    }
    @Test fun asrPreservesWavLanguageAndRejectsEmptyText() = runBlocking(Dispatchers.IO) {
        h.session(); h.pcmChunk()
        val row = h.repo.sessionForExport(h.id).chunks.single()
        val t = ScriptedChatTransport()
        t.enqueue(TranscriptionResponse(""))
        try { AsrClient(t, h.settings).transcribe(row, Language.EN); fail("Expected empty transcript") }
        catch (_: MalformedLlmOutputException) { }
        val req = t.transcriptionRequests.single()
        assertEquals(Language.EN, req.language)
        assertArrayEquals(row.audioBlob, req.audioWav)
        assertEquals(h.settings.snapshot().asrModel, req.model)
    }
    @Test fun analysisDropsUnknownTargetsFamiliesAndInvalidQuote() = runBlocking(Dispatchers.IO) {
        h.session(); h.script(); h.start(); h.pcmChunk(); h.drain()
        // Produce a reanalysis job through the real continuation path.
        h.killWorkers()
        val chunkId = h.pcmChunk()
        h.repo.claimNextChunkForAsr(h.id)
        h.repo.saveTranscript(TranscriptResult(chunkId, "More."))
        val row = h.repo.claimNextChunkForSegmentation(h.id)!!
        val t = ScriptedChatTransport()
        t.enqueue(PipelineHarness.chat("""{"statements":[{"text":"More.","continuesPrevious":true,"speakerTag":"S1","endsProvisionally":false}]}"""))
        h.repo.applySegmentation(row.id, h.id, StatementProcessor(t).segment(row, "tail", listOf("S1"), Language.EN))
        val ctx = h.repo.claimNextAnalysisJob(h.id)!!
        t.enqueue(PipelineHarness.chat("""{"annotations":[{"statementSeq":1,"family":"FACT_CHECK","subType":"test","note":"valid","quote":"invented"},{"statementSeq":1,"family":"OTHER","subType":"test","note":"bad"},{"statementSeq":999,"family":"FACT_CHECK","subType":"test","note":"bad"}],"claims":[]}"""))
        val engine = AnalysisEngine(t)
        var malformed = 0; engine.onMalformed = { malformed++ }
        val result = engine.analyze(ctx)
        assertEquals(2, malformed); assertEquals(1, result.annotations.size)
        assertNull(result.annotations.single().quote)
        assertEquals(ctx.targets.single().id, result.annotations.single().statementId)
    }
}
