package com.nitpicker.data.db

import com.nitpicker.domain.*
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AppDatabaseTest {
    private lateinit var db: AppDatabase

    @Before
    fun setup() {
        db =
            AppDatabase.inMemoryBuilder(RuntimeEnvironment.getApplication())
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun close() {
        db.close()
    }

    private fun session() =
        db.sessions()
            .insert(
                SessionRow(
                    startedAt = 1,
                    language = Language.DE,
                    asrModel = "test",
                    retentionMinutes = 0,
                    chunkMaxSec = 8,
                    vadSilenceMs = 900,
                )
            )

    @Test
    fun fifoClaimsAreExclusiveAndRecoverable() {
        val id = session()
        listOf(2L, 0L, 1L).forEach {
            db.chunks()
                .insert(
                    AudioChunkRow(
                        sessionId = id,
                        seq = it,
                        startedAtOffsetMs = it * 1000,
                        durationMs = 1000,
                        audioBlob = byteArrayOf(1, 2),
                    )
                )
        }
        val executor = Executors.newFixedThreadPool(3)
        try {
            val results =
                executor
                    .invokeAll(
                        List(3) {
                            java.util.concurrent.Callable { db.chunks().claimNextForAsr(id)!! }
                        }
                    )
                    .map { it.get() }
            assertEquals(setOf(0L, 1L, 2L), results.map { it.seq }.toSet())
            assertTrue(results.all { it.state == ChunkState.ASR_PENDING && it.attemptCount == 1 })
            assertNull(db.chunks().claimNextForAsr(id))
            db.chunks().resetInFlightToCaptured(id)
            assertEquals(0L, db.chunks().claimNextForAsr(id)!!.seq)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun segmentationMarkerIsSessionScopedAndRestartable() {
        val a = session()
        val b = session()
        val ca =
            db.chunks()
                .insert(
                    AudioChunkRow(sessionId = a, seq = 0, startedAtOffsetMs = 0, durationMs = 1)
                )
        val cb =
            db.chunks()
                .insert(
                    AudioChunkRow(sessionId = b, seq = 0, startedAtOffsetMs = 0, durationMs = 1)
                )
        db.chunks().saveTranscript(ca, "a")
        db.chunks().saveTranscript(cb, "b")
        assertEquals(ca, db.chunks().claimNextForSegmentation(a)!!.id)
        assertNull(db.chunks().claimNextForSegmentation(a))
        db.chunks().resetInFlightToCaptured(a)
        assertEquals(ca, db.chunks().claimNextForSegmentation(a)!!.id)
        db.jobs()
            .insert(
                AnalysisJobRow(
                    sessionId = b,
                    chunkSeq = 0,
                    targetStatementIds = emptyList(),
                    reason = AnalysisJobReason.CHUNK,
                )
            )
        assertNull(db.chunks().claimNextForSegmentation(b))
    }

    @Test
    fun jobClaimsAndJsonIdsRoundTrip() {
        val id = session()
        val job =
            db.jobs()
                .insert(
                    AnalysisJobRow(
                        sessionId = id,
                        chunkSeq = 1,
                        targetStatementIds = listOf(12, 345),
                        reason = AnalysisJobReason.CHUNK,
                    )
                )
        val claimed = db.jobs().claimNextPending(id)!!
        assertEquals(job, claimed.id)
        assertEquals(listOf(12L, 345L), claimed.targetStatementIds)
        assertEquals(1, claimed.attemptCount)
        assertNull(db.jobs().claimNextPending(id))
        db.jobs().resetInFlightToPending(id)
        assertEquals(job, db.jobs().claimNextPending(id)!!.id)
        db.jobs().markDone(job)
        assertTrue(db.jobs().existsDoneForStatement(345))
    }

    @Test
    fun databaseEnforcesChecksUniqueForeignKeysAndCascade() {
        val id = session()
        fun rejects(sql: String) {
            try {
                db.openHelper.writableDatabase.execSQL(sql)
                fail(sql)
            } catch (_: android.database.SQLException) {}
        }
        rejects("UPDATE sessions SET language='FR' WHERE id=$id")
        rejects("UPDATE sessions SET status='UNKNOWN' WHERE id=$id")
        val c = AudioChunkRow(sessionId = id, seq = 0, startedAtOffsetMs = 0, durationMs = 1)
        db.chunks().insert(c)
        try {
            db.chunks().insert(c)
            fail("duplicate sequence")
        } catch (_: android.database.SQLException) {}
        rejects("UPDATE audio_chunks SET state='BAD'")
        val s =
            db.statements()
                .insertAll(
                    listOf(
                        StatementRow(
                            sessionId = id,
                            seq = 1,
                            speakerTag = "S1",
                            text = "hello",
                            startedAtOffsetMs = 0,
                            endedAtOffsetMs = 1,
                            state = StatementState.PROVISIONAL,
                            sourceChunkIds = listOf(1),
                        )
                    )
                )
                .single()
        rejects("UPDATE statements SET state='BAD'")
        db.annotations()
            .insertAll(
                listOf(
                    AnnotationRow(
                        statementId = s,
                        family = AnnotationFamily.FACT_CHECK,
                        subType = "test",
                        note = "note",
                        quote = null,
                        createdAtOffsetMs = 1,
                    )
                )
            )
        rejects("UPDATE annotations SET family='BAD'")
        db.claims()
            .insertAll(
                listOf(
                    ClaimRow(
                        sessionId = id,
                        statementId = s,
                        compactText = "claim",
                        createdAtOffsetMs = 1,
                    )
                )
            )
        db.mappings().upsert(SpeakerMappingRow(id, "S1", "Name"))
        db.jobs()
            .insert(
                AnalysisJobRow(
                    sessionId = id,
                    chunkSeq = 0,
                    targetStatementIds = listOf(s),
                    reason = AnalysisJobReason.CHUNK,
                )
            )
        rejects("UPDATE analysis_jobs SET reason='BAD'")
        rejects("UPDATE analysis_jobs SET state='BAD'")
        db.sessions().delete(id)
        listOf(
                "audio_chunks",
                "statements",
                "annotations",
                "claims",
                "speaker_mappings",
                "analysis_jobs",
            )
            .forEach { table ->
                db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
                    it.moveToFirst()
                    assertEquals(table, 0, it.getInt(0))
                }
            }
        try {
            db.chunks().insert(c.copy(sessionId = 999))
            fail("foreign key")
        } catch (_: android.database.SQLException) {}
    }

    @Test
    fun terminalFailureClearsBlobAndFlowsReflectQueue() = runBlocking {
        val id = session()
        val chunk =
            db.chunks()
                .insert(
                    AudioChunkRow(
                        sessionId = id,
                        seq = 1,
                        startedAtOffsetMs = 0,
                        durationMs = 1,
                        audioBlob = byteArrayOf(42),
                    )
                )
        assertEquals(1, db.chunks().pendingAsrCountFlow(id).first())
        db.chunks().markFailed(chunk, "failed")
        assertNull(db.chunks().loadBlob(chunk))
        assertEquals(0, db.chunks().pendingAsrCountFlow(id).first())
    }

    @Test
    fun fileDatabaseReopensWithSameSchemaAndData() {
        val context = RuntimeEnvironment.getApplication()
        val name = "schema-reopen-test"
        context.deleteDatabase(name)
        val disk = AppDatabase.builder(context, name).allowMainThreadQueries().build()
        val id =
            disk
                .sessions()
                .insert(
                    SessionRow(
                        startedAt = 1,
                        language = Language.EN,
                        asrModel = "test",
                        retentionMinutes = 0,
                        chunkMaxSec = 8,
                        vadSilenceMs = 900,
                    )
                )
        disk.close()
        val reopened = AppDatabase.builder(context, name).allowMainThreadQueries().build()
        try {
            assertEquals(Language.EN, reopened.sessions().getById(id)!!.language)
        } finally {
            reopened.close()
            context.deleteDatabase(name)
        }
    }
}
