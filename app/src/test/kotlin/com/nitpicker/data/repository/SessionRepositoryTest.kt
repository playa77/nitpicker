package com.nitpicker.data.repository

import com.nitpicker.data.db.*
import com.nitpicker.domain.*
import com.nitpicker.llm.SegmentedStatement
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SessionRepositoryTest {
    private lateinit var db: AppDatabase
    private lateinit var repo: SessionRepository

    @Before
    fun setup() {
        db = AppDatabase.inMemoryBuilder(RuntimeEnvironment.getApplication()).build()
        repo = SessionRepository(db)
    }

    @After
    fun close() {
        db.close()
    }

    private suspend fun session() =
        repo.createSession(SessionParams(Language.DE, "test", 0, 8, 900))

    private suspend fun segment(
        id: Long,
        seq: Long,
        text: String,
        continuation: Boolean = false,
        provisional: Boolean = false,
    ): SegmentationApplied {
        val chunk =
            repo.enqueueChunk(
                id,
                AudioChunkData(
                    id,
                    seq,
                    byteArrayOf(1, 2),
                    startedAtOffsetMs = seq * 1000,
                    durationMs = 1000,
                ),
            )
        assertEquals(chunk, repo.claimNextChunkForAsr(id)!!.id)
        repo.saveTranscript(TranscriptResult(chunk, text))
        assertEquals(chunk, repo.claimNextChunkForSegmentation(id)!!.id)
        return repo.applySegmentation(
            chunk,
            id,
            SegmentationResult(
                chunk,
                listOf(SegmentedStatement(text, continuation, "S1", provisional)),
            ),
        )
    }

    @Test
    fun allContinuationAndFinalCombinations() = runBlocking {
        for (continuation in listOf(false, true)) for (provisional in listOf(false, true)) {
            val id = session()
            val first = segment(id, 0, "First", provisional = true).createdIds.single()
            val next = segment(id, 1, "second", continuation, provisional)
            val rows = repo.sessionForExport(id).statements
            assertEquals(if (continuation) 1 else 2, rows.size)
            assertEquals(
                if (provisional) StatementState.PROVISIONAL else StatementState.FINAL,
                rows.last().state,
            )
            if (continuation) {
                assertEquals(first, next.extendedIds.single())
                assertEquals("First second", rows.single().text)
                assertEquals(2, rows.single().sourceChunkIds.size)
            } else {
                assertEquals(StatementState.FINAL, rows.first().state)
                assertEquals(2L, rows.last().seq)
            }
        }
    }

    @Test
    fun analysisReplacesAnnotationsCapsClaimsAndLoadsFreshContinuation() = runBlocking {
        val id = session()
        val first = segment(id, 0, "Revenue was 4 million", provisional = true).createdIds.single()
        val job = repo.claimNextAnalysisJob(id)!!
        repo.saveAnalysis(
            job.job.id,
            id,
            AnalysisResult(
                job.job.id,
                listOf(
                    AnalysisAnnotationSpec(
                        first,
                        1,
                        AnnotationFamily.FACT_CHECK,
                        "check",
                        "old",
                        "Revenue",
                    )
                ),
                (0..99).map { "S1: claim $it" },
            ),
        )
        assertEquals(ChunkState.ANALYZED, repo.sessionForExport(id).chunks.single().state)
        segment(id, 1, "and now 5 million", continuation = true)
        val reanalysis = repo.claimNextAnalysisJob(id)!!
        assertEquals(AnalysisJobReason.REANALYSIS, reanalysis.job.reason)
        assertEquals("Revenue was 4 million and now 5 million", reanalysis.targets.single().text)
        assertEquals(80, reanalysis.digest.size)
        assertEquals("S1: claim 20", reanalysis.digest.first().compactText)
        repo.saveAnalysis(
            reanalysis.job.id,
            id,
            AnalysisResult(
                reanalysis.job.id,
                listOf(
                    AnalysisAnnotationSpec(
                        first,
                        1,
                        AnnotationFamily.LOGIC_RHETORIC,
                        "check",
                        "new",
                        "invented",
                    )
                ),
                emptyList(),
            ),
        )
        val annotations = repo.sessionForExport(id).annotations
        assertEquals(1, annotations.size)
        assertEquals("new", annotations.single().note)
        assertNull(annotations.single().quote)
    }

    @Test
    fun replayRecoveryAndRollbackPreserveQueues() = runBlocking {
        val id = session()
        segment(id, 0, "test")
        val context = repo.claimNextAnalysisJob(id)!!
        try {
            repo.saveAnalysis(
                context.job.id,
                id,
                AnalysisResult(
                    context.job.id,
                    listOf(
                        AnalysisAnnotationSpec(999, 1, AnnotationFamily.FACT_CHECK, "x", "x", null)
                    ),
                    emptyList(),
                ),
            )
            fail()
        } catch (_: IllegalArgumentException) {}
        assertTrue(repo.sessionForExport(id).annotations.isEmpty())
        repo.recoverInFlight(id)
        assertEquals(context.job.id, repo.claimNextAnalysisJob(id)!!.job.id)
        repo.saveAnalysis(
            context.job.id,
            id,
            AnalysisResult(context.job.id, emptyList(), emptyList()),
        )
        repo.saveAnalysis(
            context.job.id,
            id,
            AnalysisResult(context.job.id, emptyList(), emptyList()),
        )
        assertNull(repo.claimNextAnalysisJob(id))
        val c = repo.sessionForExport(id).chunks.single()
        // Completed segmentation cannot be claimed or applied twice.
        assertNull(repo.claimNextChunkForSegmentation(id))
        repo.markDiscarded(c.id, "retained.wav")
        assertNull(repo.loadBlob(c.id))
        assertEquals("retained.wav", repo.sessionForExport(id).chunks.single().retainedPath)
    }

    @Test
    fun exportMappingsFinalizationStatusAndDelete() = runBlocking {
        val id = session()
        segment(id, 0, "hello", provisional = true)
        repo.upsertMapping(SpeakerMappingRow(id, "S1", "Alice"))
        repo.setExportUri(id, "content://export")
        repo.finalizeSession(id)
        repo.setStatus(id, SessionStatus.COMPLETED)
        val model = repo.sessionForExport(id)
        assertEquals(StatementState.FINAL, model.statements.single().state)
        assertEquals("S1", model.statements.single().speakerTag)
        assertEquals("Alice", model.mappings.single().displayName)
        assertNotNull(model.session.endedAt)
        assertTrue(repo.activeOrStopping().isEmpty())
        assertEquals(0 to 1, repo.queueDepthsFlow(id).first())
        repo.deleteSession(id)
        assertTrue(repo.sessionsFlow().first().isEmpty())
    }

    @Test
    fun rollingWindowIsBoundedChronologicalAndCrossSessionIsRejected() = runBlocking {
        val id = session()
        repeat(23) { segment(id, it.toLong(), "statement $it") }
        var last: AnalysisJobContext? = null
        repeat(23) { last = repo.claimNextAnalysisJob(id) }
        assertEquals((3L..22L).toList(), last!!.window.map { it.seq })
        val other = session()
        try {
            repo.saveAnalysis(
                last!!.job.id,
                other,
                AnalysisResult(last!!.job.id, emptyList(), emptyList()),
            )
            fail()
        } catch (_: IllegalArgumentException) {}
    }
}
