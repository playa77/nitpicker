package com.nitpicker.data.db

import androidx.room.*
import com.nitpicker.domain.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Insert fun insert(s: SessionRow): Long

    @Query("UPDATE sessions SET status=:status, ended_at=:endedAt WHERE id=:id")
    fun setStatus(id: Long, status: SessionStatus, endedAt: Long?)

    @Query("UPDATE sessions SET export_uri=:uri WHERE id=:id")
    fun setExportUri(id: Long, uri: String)

    @Query("SELECT * FROM sessions WHERE id=:id") fun getById(id: Long): SessionRow?

    @Query("SELECT * FROM sessions WHERE status IN ('ACTIVE','STOPPING') ORDER BY id")
    fun activeOrStopping(): List<SessionRow>

    @Query("SELECT * FROM sessions ORDER BY started_at DESC, id DESC")
    fun sessionsFlow(): Flow<List<SessionRow>>

    @Query("DELETE FROM sessions WHERE id=:id") fun delete(id: Long)
}

@Dao
abstract class ChunkDao {
    private val segmentationClaims = mutableSetOf<Long>()

    @Insert abstract fun insert(c: AudioChunkRow): Long

    @Query(
        "SELECT * FROM audio_chunks WHERE session_id=:sessionId AND state='CAPTURED' ORDER BY seq LIMIT 1"
    )
    abstract fun nextCaptured(sessionId: Long): AudioChunkRow?

    @Query(
        "UPDATE audio_chunks SET state='ASR_PENDING',attempt_count=attempt_count+1 WHERE id=:id AND state='CAPTURED'"
    )
    abstract fun claimAsr(id: Long): Int

    @Transaction
    open fun claimNextForAsr(sessionId: Long): AudioChunkRow? {
        val c = nextCaptured(sessionId) ?: return null
        return if (claimAsr(c.id) == 1) get(c.id) else null
    }

    @Query(
        "UPDATE audio_chunks SET state='CAPTURED' WHERE session_id=:sessionId AND state='ASR_PENDING'"
    )
    abstract fun resetAsr(sessionId: Long)

    @Transaction
    open fun resetInFlightToCaptured(sessionId: Long) {
        resetAsr(sessionId)
        synchronized(segmentationClaims) {
            segmentationClaims.removeAll(bySession(sessionId).map { it.id }.toSet())
        }
    }

    @Query(
        "UPDATE audio_chunks SET transcript=:transcript,state='ASR_DONE',last_error=NULL WHERE id=:id"
    )
    abstract fun saveTranscript(id: Long, transcript: String)

    @Query("UPDATE audio_chunks SET state='FAILED',last_error=:error,audio_blob=NULL WHERE id=:id")
    abstract fun markFailed(id: Long, error: String)

    @Query("SELECT * FROM segmentation_pending WHERE session_id=:sessionId ORDER BY seq")
    abstract fun segmentationPending(sessionId: Long): List<SegmentationPending>

    @Query(
        "UPDATE audio_chunks SET state='ASR_DONE' WHERE id=:id AND state='ASR_DONE' AND NOT EXISTS (SELECT 1 FROM analysis_jobs j WHERE j.session_id=audio_chunks.session_id AND j.chunk_seq=audio_chunks.seq AND j.reason='CHUNK')"
    )
    abstract fun guardSegmentation(id: Long): Int

    @Transaction
    open fun claimNextForSegmentation(sessionId: Long): AudioChunkRow? =
        synchronized(segmentationClaims) {
            val c =
                segmentationPending(sessionId)
                    .firstOrNull { it.chunk.id !in segmentationClaims }
                    ?.chunk ?: return@synchronized null
            if (guardSegmentation(c.id) != 1) return@synchronized null
            segmentationClaims.add(c.id)
            c
        }

    fun releaseSegmentationClaim(id: Long) {
        synchronized(segmentationClaims) { segmentationClaims.remove(id) }
    }

    @Query("UPDATE audio_chunks SET state='ANALYZED' WHERE id=:id")
    abstract fun markAnalyzed(id: Long)

    @Query("UPDATE audio_chunks SET state='DISCARDED',audio_blob=NULL WHERE id=:id")
    abstract fun markDiscarded(id: Long)

    @Query("UPDATE audio_chunks SET audio_blob=NULL WHERE id=:id") abstract fun nullBlob(id: Long)

    @Query("UPDATE audio_chunks SET retained_path=:path WHERE id=:id")
    abstract fun setRetainedPath(id: Long, path: String?)

    @Query(
        "SELECT COUNT(*) FROM audio_chunks WHERE session_id=:sessionId AND state IN ('CAPTURED','ASR_PENDING')"
    )
    abstract fun pendingAsrCountFlow(sessionId: Long): Flow<Int>

    @Query("SELECT * FROM audio_chunks WHERE session_id=:sessionId ORDER BY seq")
    abstract fun bySession(sessionId: Long): List<AudioChunkRow>

    @Query("SELECT audio_blob FROM audio_chunks WHERE id=:id")
    abstract fun loadBlob(id: Long): ByteArray?

    @Query("SELECT * FROM audio_chunks WHERE id=:id") abstract fun get(id: Long): AudioChunkRow?
}

@Dao
abstract class StatementDao {
    @Insert abstract fun insertAll(rows: List<StatementRow>): List<Long>

    @Query("SELECT * FROM statements WHERE session_id=:sessionId ORDER BY seq DESC LIMIT 1")
    abstract fun lastStatement(sessionId: Long): StatementRow?

    @Query("SELECT * FROM statements WHERE id=:id") abstract fun get(id: Long): StatementRow?

    @Update abstract fun update(row: StatementRow)

    @Transaction
    open fun extendStatement(id: Long, appendText: String, endOffsetMs: Long, chunkId: Long) {
        val s = requireNotNull(get(id))
        update(
            s.copy(
                text = listOf(s.text, appendText).filter { it.isNotBlank() }.joinToString(" "),
                endedAtOffsetMs = endOffsetMs,
                sourceChunkIds = (s.sourceChunkIds + chunkId).distinct(),
            )
        )
    }

    @Query("UPDATE statements SET state='FINAL' WHERE id=:id AND state='PROVISIONAL'")
    abstract fun finalizeIfProvisional(id: Long)

    @Query(
        "UPDATE statements SET state='FINAL' WHERE session_id=:sessionId AND state='PROVISIONAL'"
    )
    abstract fun finalizeAllProvisional(sessionId: Long)

    @Query("SELECT * FROM statements WHERE session_id=:sessionId ORDER BY seq")
    abstract fun statementsFlow(sessionId: Long): Flow<List<StatementRow>>

    @Query("SELECT * FROM statements WHERE session_id=:sessionId ORDER BY seq")
    abstract fun bySession(sessionId: Long): List<StatementRow>

    @Query("SELECT * FROM statements WHERE id IN (:ids) ORDER BY seq")
    abstract fun byIds(ids: List<Long>): List<StatementRow>

    @Query(
        "SELECT * FROM (SELECT * FROM statements WHERE session_id=:sessionId AND seq<:beforeSeq ORDER BY seq DESC LIMIT :limit) ORDER BY seq"
    )
    abstract fun windowBefore(sessionId: Long, beforeSeq: Long, limit: Int): List<StatementRow>

    @Query(
        "SELECT speaker_tag FROM (SELECT * FROM statements WHERE session_id=:sessionId AND speaker_tag IS NOT NULL ORDER BY seq DESC LIMIT :limit)"
    )
    abstract fun recentTags(sessionId: Long, limit: Int): List<String>
}

@Dao
interface AnnotationDao {
    @Insert fun insertAll(rows: List<AnnotationRow>)

    @Query("DELETE FROM annotations WHERE statement_id IN (:statementIds)")
    fun deleteForStatements(statementIds: List<Long>)

    @Query("SELECT * FROM annotations WHERE statement_id IN (:ids) ORDER BY id")
    fun annotationsForStatementsFlow(ids: List<Long>): Flow<List<AnnotationRow>>

    @Query("SELECT * FROM annotations WHERE statement_id IN (:ids) ORDER BY id")
    fun byStatements(ids: List<Long>): List<AnnotationRow>

    @Query(
        "SELECT COUNT(*) FROM annotations WHERE statement_id IN (SELECT id FROM statements WHERE session_id=:sessionId)"
    )
    fun countForSession(sessionId: Long): Int
}

@Dao
interface ClaimDao {
    @Insert fun insertAll(rows: List<ClaimRow>)

    @Query("SELECT * FROM claims WHERE session_id=:sessionId ORDER BY id")
    fun digest(sessionId: Long): List<ClaimRow>

    @Query(
        "DELETE FROM claims WHERE session_id=:sessionId AND id NOT IN (SELECT id FROM claims WHERE session_id=:sessionId ORDER BY id DESC LIMIT :cap)"
    )
    fun deleteOldestBeyond(sessionId: Long, cap: Int)
}

@Dao
interface SpeakerMappingDao {
    @Upsert fun upsert(m: SpeakerMappingRow)

    @Query("SELECT * FROM speaker_mappings WHERE session_id=:sessionId ORDER BY raw_tag")
    fun forSessionFlow(sessionId: Long): Flow<List<SpeakerMappingRow>>

    @Query("SELECT * FROM speaker_mappings WHERE session_id=:sessionId ORDER BY raw_tag")
    fun bySession(sessionId: Long): List<SpeakerMappingRow>
}

@Dao
abstract class AnalysisJobDao {
    @Insert abstract fun insert(j: AnalysisJobRow): Long

    @Query(
        "SELECT * FROM analysis_jobs WHERE session_id=:sessionId AND state='PENDING' ORDER BY id LIMIT 1"
    )
    abstract fun nextPending(sessionId: Long): AnalysisJobRow?

    @Query(
        "UPDATE analysis_jobs SET state='IN_FLIGHT',attempt_count=attempt_count+1 WHERE id=:id AND state='PENDING'"
    )
    abstract fun claim(id: Long): Int

    @Transaction
    open fun claimNextPending(sessionId: Long): AnalysisJobRow? {
        val j = nextPending(sessionId) ?: return null
        return if (claim(j.id) == 1) get(j.id) else null
    }

    @Query("SELECT * FROM analysis_jobs WHERE id=:id") abstract fun get(id: Long): AnalysisJobRow?

    @Query("SELECT * FROM analysis_jobs WHERE session_id=:sessionId ORDER BY id")
    abstract fun bySession(sessionId: Long): List<AnalysisJobRow>

    @Query(
        "UPDATE analysis_jobs SET state='PENDING' WHERE session_id=:sessionId AND state='IN_FLIGHT'"
    )
    abstract fun resetInFlightToPending(sessionId: Long)

    @Query("UPDATE analysis_jobs SET state='DONE',last_error=NULL WHERE id=:id")
    abstract fun markDone(id: Long)

    @Query("UPDATE analysis_jobs SET state='FAILED',last_error=:error WHERE id=:id")
    abstract fun markFailed(id: Long, error: String)

    @Query(
        "SELECT COUNT(*) FROM analysis_jobs WHERE session_id=:sessionId AND state IN ('PENDING','IN_FLIGHT')"
    )
    abstract fun pendingCountFlow(sessionId: Long): Flow<Int>

    @Query("SELECT * FROM analysis_jobs WHERE state='DONE'")
    abstract fun doneJobs(): List<AnalysisJobRow>

    fun existsDoneForStatement(statementId: Long): Boolean =
        doneJobs().any { statementId in it.targetStatementIds }
}
