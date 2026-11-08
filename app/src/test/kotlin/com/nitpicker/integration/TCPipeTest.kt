package com.nitpicker.integration

import com.nitpicker.harness.PipelineHarness
import com.nitpicker.domain.*
import com.nitpicker.data.db.SpeakerMappingRow
import com.nitpicker.export.SpeakerNames
import com.nitpicker.llm.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TCPipeTest {
    private lateinit var h: PipelineHarness
    @Before fun setup() { h = PipelineHarness() }
    @After fun close() = runBlocking(Dispatchers.IO) { h.close() }

    @Test fun tcPipe1HappyPath() = runBlocking(Dispatchers.IO) {
        h.session()
        h.transport.enqueue(TranscriptionResponse("Berlin is the capital of France. So every capital is Berlin."))
        h.transport.enqueue(PipelineHarness.chat("""{"statements":[{"text":"Berlin is the capital of France.","continuesPrevious":false,"speakerTag":"S1","endsProvisionally":false},{"text":"So every capital is Berlin.","continuesPrevious":false,"speakerTag":"S2","endsProvisionally":false}]}"""))
        h.transport.enqueue(PipelineHarness.chat("""{"annotations":[{"statementSeq":1,"family":"FACT_CHECK","subType":"mismatch","note":"Berlin is Germany's capital."},{"statementSeq":2,"family":"LOGIC_RHETORIC","subType":"false-conclusion","note":"The conclusion does not follow."}],"claims":["S1: Berlin is capital"]}"""))
        h.start(); h.pcmChunk(); h.drain()
        val m = h.repo.sessionForExport(h.id)
        assertEquals(2, m.statements.size); assertEquals(2, m.annotations.size)
        assertEquals(ChunkState.DISCARDED, m.chunks.single().state)
        assertNull(m.chunks.single().audioBlob)
        assertEquals(1, h.db.claims().digest(h.id).size)
        assertTrue(h.transport.transcriptionRequests.single().audioWav.take(4).toByteArray().contentEquals("RIFF".toByteArray()))
    }
    @Test fun tcPipe2ContinuationReplacesAnnotations() = runBlocking(Dispatchers.IO) {
        h.session(); h.script(text = "The budget is", provisional = true, note = "old")
        h.start(); h.pcmChunk(); h.drain()
        val original = h.repo.sessionForExport(h.id).statements.single()
        h.script(text = "four million.", continuation = true, note = "new", extraAnalysis = true)
        h.pcmChunk(); h.drain()
        val m = h.repo.sessionForExport(h.id)
        assertEquals(original.id, m.statements.single().id)
        assertEquals("The budget is four million.", m.statements.single().text)
        assertEquals(listOf("new"), m.annotations.map { it.note })
        assertTrue(h.db.jobs().bySession(h.id).any { it.reason == AnalysisJobReason.REANALYSIS && it.state == JobState.DONE })
    }
    @Test fun tcPipe3OfflineBackfill() = runBlocking(Dispatchers.IO) {
        h.session(); h.gate.setState(NetworkState.OFFLINE)
        // One cross-endpoint script, barriers admit the next endpoint while workers run independently.
        h.script(seq = 1); h.script(seq = 2); h.script(seq = 3)
        h.start(); repeat(3) { h.pcmChunk() }
        delay(150); assertEquals(0, h.transport.callCount)
        assertEquals(3, h.repo.sessionForExport(h.id).chunks.size)
        h.gate.setState(NetworkState.ONLINE); h.drain()
        val m = h.repo.sessionForExport(h.id)
        assertEquals(listOf(1L, 2L, 3L), m.statements.map { it.seq })
        assertEquals(3, m.annotations.size)
        assertEquals(listOf(0L, 1600L, 3200L), m.chunks.map { it.startedAtOffsetMs })
    }
    @Test fun tcPipe4StopDrainExport() = runBlocking(Dispatchers.IO) {
        h.session(); h.script(); h.start(); h.pcmChunk()
        val result = h.complete()
        val m = h.repo.sessionForExport(h.id)
        assertEquals(SessionStatus.COMPLETED, m.session.status)
        assertEquals(result.uri, m.session.exportUri)
        assertTrue(h.sink.files.values.single().contains(m.statements.single().text))
        assertTrue(h.sink.files.values.single().contains("*Fact-check:"))
        assertEquals(1, result.annotationCount)
    }
    @Test fun tcPipe5RecoveryResetsProductionClaims() = runBlocking(Dispatchers.IO) {
        h.session(); h.pcmChunk()
        val claimed = h.repo.claimNextChunkForAsr(h.id)!!
        assertEquals(ChunkState.ASR_PENDING, claimed.state)
        h.script(); h.restart(); h.drain()
        assertEquals(ChunkState.DISCARDED, h.repo.sessionForExport(h.id).chunks.single().state)
        h.analysisBarrier = CompletableDeferred()
        h.script(text = "Second.", seq = 2)
        h.pcmChunk()
        h.await { h.db.jobs().bySession(h.id).any { it.state == JobState.IN_FLIGHT } }
        h.killWorkers()
        h.analysisBarrier = null
        h.restart(); h.drain()
        assertEquals(2, h.repo.sessionForExport(h.id).statements.size)
        assertTrue(h.db.jobs().bySession(h.id).all { it.state == JobState.DONE })
    }
    @Test fun tcPipe6AsrTerminalFailureGapAndContinue() = runBlocking(Dispatchers.IO) {
        h.session(2)
        repeat(3) { h.transport.enqueueError(OpenRouterTimeoutException()) }
        h.start(); h.pcmChunk()
        h.await { h.repo.sessionForExport(h.id).chunks.single().state == ChunkState.FAILED && h.repo.loadBlob(h.repo.sessionForExport(h.id).chunks.single().id) == null }
        h.script(seq = 1); h.pcmChunk(); h.complete()
        val m = h.repo.sessionForExport(h.id)
        assertEquals(listOf(ChunkState.FAILED, ChunkState.DISCARDED), m.chunks.map { it.state })
        assertEquals(1, m.statements.size)
        assertTrue(h.sink.files.values.single().contains("[transcription gap 00:00:00]"))
        assertEquals(2, h.retention.filesForSession(h.id).size)
    }
    @Test fun tcPipe7RetentionWindowZeroAndPrune() = runBlocking(Dispatchers.IO) {
        h.session(2); h.start()
        // 76 real VAD chunks cross 120s without offset fixtures.
        repeat(76) { index -> h.script(seq = index + 1L); h.pcmChunk(); h.drain() }
        assertEquals(75, h.retention.filesForSession(h.id).size)
        assertTrue(h.repo.sessionForExport(h.id).chunks.all { it.audioBlob == null })
        h.complete()
        // Create all retained directories through pipeline terminal transitions.
        repeat(11) {
            h.session(2); h.script(); h.restart(); h.pcmChunk(); h.complete()
        }
        h.retention.prune()
        assertEquals(10, java.io.File(h.dir, "retained").listFiles()!!.count { it.isDirectory })
        h.session(0); h.script(); h.restart(); h.pcmChunk(); h.complete()
        assertTrue(h.retention.filesForSession(h.id).isEmpty())
    }
    @Test fun tcPipe8ProvisionalFinalization() = runBlocking(Dispatchers.IO) {
        h.session(); h.script(provisional = true); h.start(); h.pcmChunk(); h.drain()
        assertEquals(StatementState.PROVISIONAL, h.repo.sessionForExport(h.id).statements.single().state)
        h.complete()
        assertEquals(StatementState.FINAL, h.repo.sessionForExport(h.id).statements.single().state)
        assertFalse(h.sink.files.values.single().contains("…"))
    }
    @Test fun tcPipe9RenameMergeExportPreservesRawTags() = runBlocking(Dispatchers.IO) {
        h.session(); h.script(tag = "S1"); h.start(); h.pcmChunk(); h.drain()
        h.script(seq = 2, tag = "S2"); h.pcmChunk(); h.complete()
        h.repo.upsertMapping(SpeakerMappingRow(h.id, "S1", displayName = "Alex"))
        h.exporter.export(h.id)
        assertTrue(h.sink.files.values.single().contains("Alex"))
        SpeakerNames.validateMerge("S1", "S2", h.repo.sessionForExport(h.id).mappings)
        h.repo.upsertMapping(SpeakerMappingRow(h.id, "S1", mergedIntoTag = "S2"))
        h.repo.upsertMapping(SpeakerMappingRow(h.id, "S2", displayName = "Team"))
        h.exporter.export(h.id)
        assertEquals(listOf("S1", "S2"), h.repo.sessionForExport(h.id).statements.map { it.speakerTag })
        assertEquals(2, Regex("Team").findAll(h.sink.files.values.single()).count())
    }
}
