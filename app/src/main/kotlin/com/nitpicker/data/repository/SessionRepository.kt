package com.nitpicker.data.repository

import androidx.room.Transaction
import com.nitpicker.data.db.*
import com.nitpicker.domain.*
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

class SessionRepository(private val db: AppDatabase) {
    private val sessions = db.sessions()
    private val chunks = db.chunks()
    private val statements = db.statements()
    private val annotations = db.annotations()
    private val claims = db.claims()
    private val jobs = db.jobs()
    private val mappings = db.mappings()

    private suspend fun <T> transaction(block: () -> T): T =
        withContext(Dispatchers.IO) { db.runInTransaction(Callable(block)) }

    @Transaction
    suspend fun createSession(params: SessionParams): Long = transaction {
        sessions.insert(
            SessionRow(
                startedAt = System.currentTimeMillis(),
                language = params.language,
                asrModel = params.asrModel,
                retentionMinutes = params.retentionMinutes,
                chunkMaxSec = params.chunkMaxSec,
                vadSilenceMs = params.vadSilenceMs,
            )
        )
    }

    @Transaction
    suspend fun enqueueChunk(sessionId: Long, chunk: AudioChunkData): Long = transaction {
        require(chunk.sessionId == sessionId)
        chunks.insert(
            AudioChunkRow(
                sessionId = sessionId,
                seq = chunk.seq,
                startedAtOffsetMs = chunk.startedAtOffsetMs,
                durationMs = chunk.durationMs,
                audioBlob = chunk.pcm16Le,
            )
        )
    }

    @Transaction
    suspend fun claimNextChunkForAsr(sessionId: Long): AudioChunkRow? = transaction {
        chunks.claimNextForAsr(sessionId)
    }

    @Transaction
    suspend fun saveTranscript(result: TranscriptResult) = transaction {
        chunks.saveTranscript(result.chunkId, result.text)
    }

    @Transaction
    suspend fun markChunkFailed(chunkId: Long, error: String) = transaction {
        chunks.markFailedPreservingAudio(chunkId, error)
        chunks.releaseSegmentationClaim(chunkId)
    }

    @Transaction
    suspend fun claimNextChunkForSegmentation(sessionId: Long): AudioChunkRow? = transaction {
        chunks.claimNextForSegmentation(sessionId)
    }

    @Transaction
    suspend fun applySegmentation(
        chunkId: Long,
        sessionId: Long,
        r: SegmentationResult,
    ): SegmentationApplied = transaction {
        require(r.chunkId == chunkId)
        val chunk = requireNotNull(chunks.get(chunkId))
        require(chunk.sessionId == sessionId && chunk.state == ChunkState.ASR_DONE)
        val existing =
            jobs.bySession(sessionId).find {
                it.reason == AnalysisJobReason.CHUNK && it.chunkSeq == chunk.seq
            }
        if (existing != null) return@transaction SegmentationApplied(emptyList(), emptyList())
        val created = mutableListOf<Long>()
        val extended = mutableListOf<Long>()
        val targets = mutableListOf<Long>()
        r.statements.forEachIndexed { index, segment ->
            val previous = statements.lastStatement(sessionId)
            val end = chunk.startedAtOffsetMs + chunk.durationMs
            if (index == 0 && segment.continuesPrevious && previous != null) {
                val needsReanalysis =
                    jobs.bySession(sessionId).any { previous.id in it.targetStatementIds } ||
                        annotations.byStatements(listOf(previous.id)).isNotEmpty()
                statements.extendStatement(previous.id, segment.text, end, chunkId)
                statements.update(
                    requireNotNull(statements.get(previous.id))
                        .copy(
                            state =
                                if (segment.endsProvisionally) StatementState.PROVISIONAL
                                else StatementState.FINAL
                        )
                )
                extended.add(previous.id)
                targets.add(previous.id)
                if (needsReanalysis)
                    jobs.insert(
                        AnalysisJobRow(
                            sessionId = sessionId,
                            chunkSeq = null,
                            targetStatementIds = listOf(previous.id),
                            reason = AnalysisJobReason.REANALYSIS,
                        )
                    )
            } else {
                if (previous != null) statements.finalizeIfProvisional(previous.id)
                val id =
                    statements
                        .insertAll(
                            listOf(
                                StatementRow(
                                    sessionId = sessionId,
                                    seq = (previous?.seq ?: 0) + 1,
                                    speakerTag = segment.speakerTag,
                                    text = segment.text,
                                    startedAtOffsetMs = chunk.startedAtOffsetMs,
                                    endedAtOffsetMs = end,
                                    state =
                                        if (segment.endsProvisionally) StatementState.PROVISIONAL
                                        else StatementState.FINAL,
                                    sourceChunkIds = listOf(chunkId),
                                )
                            )
                        )
                        .single()
                created.add(id)
                targets.add(id)
            }
        }
        jobs.insert(
            AnalysisJobRow(
                sessionId = sessionId,
                chunkSeq = chunk.seq,
                targetStatementIds = targets.distinct(),
                reason = AnalysisJobReason.CHUNK,
            )
        )
        chunks.releaseSegmentationClaim(chunkId)
        SegmentationApplied(created, extended)
    }

    @Transaction
    suspend fun claimNextAnalysisJob(sessionId: Long): AnalysisJobContext? = transaction {
        val job = jobs.claimNextPending(sessionId) ?: return@transaction null
        val targets = statements.byIds(job.targetStatementIds)
        AnalysisJobContext(
            job,
            targets,
            statements.windowBefore(
                sessionId,
                targets.minOfOrNull { it.seq } ?: Long.MAX_VALUE,
                ROLLING_WINDOW_STATEMENTS,
            ),
            claims.digest(sessionId),
            requireNotNull(sessions.getById(sessionId)).language,
        )
    }

    @Transaction
    suspend fun saveAnalysis(jobId: Long, sessionId: Long, result: AnalysisResult) = transaction {
        val job = requireNotNull(jobs.get(jobId))
        require(job.sessionId == sessionId && result.jobId == jobId)
        if (job.state == JobState.DONE) return@transaction
        require(job.state == JobState.IN_FLIGHT)
        val targets = statements.byIds(job.targetStatementIds).associateBy { it.id }
        require(result.annotations.all { targets[it.statementId]?.seq == it.seq })
        annotations.deleteForStatements(job.targetStatementIds)
        annotations.insertAll(
            result.annotations.map { a ->
                AnnotationRow(
                    statementId = a.statementId,
                    family = a.family,
                    subType = a.subType,
                    note = a.note,
                    quote = a.quote?.takeIf { targets.getValue(a.statementId).text.contains(it) },
                    createdAtOffsetMs =
                        targets.getValue(a.statementId).endedAtOffsetMs
                            ?: targets.getValue(a.statementId).startedAtOffsetMs,
                )
            }
        )
        if (result.claims.isNotEmpty()) {
            val first = requireNotNull(targets.values.minByOrNull { it.seq })
            claims.insertAll(
                result.claims.map { text ->
                    val speaker = text.substringBefore(':', "")
                    val owner = targets.values.firstOrNull { it.speakerTag == speaker } ?: first
                    ClaimRow(
                        sessionId = sessionId,
                        statementId = owner.id,
                        compactText = text,
                        createdAtOffsetMs = owner.endedAtOffsetMs ?: owner.startedAtOffsetMs,
                    )
                }
            )
            claims.deleteOldestBeyond(sessionId, CLAIMS_DIGEST_CAP)
        }
        jobs.markDone(jobId)
        if (job.reason == AnalysisJobReason.CHUNK)
            chunks
                .bySession(sessionId)
                .find { it.seq == job.chunkSeq }
                ?.let { chunks.markAnalyzed(it.id) }
    }

    @Transaction
    suspend fun finalizeSession(sessionId: Long) = transaction {
        statements.finalizeAllProvisional(sessionId)
    }

    @Transaction
    suspend fun setStatus(sessionId: Long, status: SessionStatus) = transaction {
        sessions.setStatus(
            sessionId,
            status,
            if (status == SessionStatus.COMPLETED || status == SessionStatus.FAILED)
                System.currentTimeMillis()
            else null,
        )
    }

    @Transaction
    suspend fun upsertMapping(m: SpeakerMappingRow) = transaction { mappings.upsert(m) }

    suspend fun sessionForExport(sessionId: Long): ExportModel = transaction {
        val rows = statements.bySession(sessionId)
        ExportModel(
            requireNotNull(sessions.getById(sessionId)),
            rows,
            annotations.byStatements(rows.map { it.id }),
            mappings.bySession(sessionId),
            chunks.bySession(sessionId),
        )
    }

    suspend fun sessionMetadata(sessionId: Long): SessionRow = transaction { requireNotNull(sessions.getById(sessionId)) }
    suspend fun terminalChunks(sessionId: Long): List<AudioChunkRow> = transaction { chunks.terminalMetadata(sessionId) }
    suspend fun chunkMetadata(sessionId: Long): List<AudioChunkRow> = transaction { chunks.metadataBySession(sessionId) }
    suspend fun segmentationContext(sessionId: Long): Pair<String?, List<String>> = transaction {
        statements.lastStatement(sessionId)?.text to statements.recentTags(sessionId, 6).reversed()
    }
    suspend fun sessionForDisplay(sessionId: Long): ExportModel = transaction {
        val rows = statements.bySession(sessionId)
        ExportModel(requireNotNull(sessions.getById(sessionId)), rows,
            annotations.byStatements(rows.map { it.id }), mappings.bySession(sessionId), chunks.metadataBySession(sessionId))
    }

    suspend fun statementsFlow(sessionId: Long): Flow<List<StatementRow>> =
        statements.statementsFlow(sessionId)

    suspend fun annotationsFlow(sessionId: Long, ids: List<Long>): Flow<List<AnnotationRow>> {
        require(transaction { statements.byIds(ids).all { it.sessionId == sessionId } })
        return annotations.annotationsForStatementsFlow(ids)
    }

    suspend fun mappingsFlow(sessionId: Long): Flow<List<SpeakerMappingRow>> =
        mappings.forSessionFlow(sessionId)

    suspend fun activeOrStopping(): List<SessionRow> = transaction { sessions.activeOrStopping() }

    suspend fun sessionsFlow(): Flow<List<SessionRow>> = sessions.sessionsFlow()

    @Transaction suspend fun deleteSession(id: Long) = transaction { sessions.delete(id) }

    suspend fun queueDepthsFlow(sessionId: Long): Flow<Pair<Int, Int>> =
        combine(chunks.pendingAsrCountFlow(sessionId), jobs.pendingCountFlow(sessionId)) { a, b ->
            a to b
        }

    // Infrastructure seams named by the worker/service traces but omitted from §5.3's listing.
    @Transaction
    suspend fun recoverInFlight(sessionId: Long) = transaction {
        val session = requireNotNull(sessions.getById(sessionId))
        if (session.asrModel !in com.nitpicker.data.settings.SettingsStore.ASR_MODELS)
            sessions.setAsrModelForRecovery(sessionId, DEFAULT_ASR_MODEL)
        chunks.resetInFlightToCaptured(sessionId)
        jobs.resetInFlightToPending(sessionId)
    }

    @Transaction
    suspend fun markDiscarded(chunkId: Long, retainedPath: String? = null) = transaction {
        chunks.setRetainedPath(chunkId, retainedPath)
        chunks.markDiscarded(chunkId)
    }

    @Transaction suspend fun nullBlob(chunkId: Long) = transaction { chunks.nullBlob(chunkId) }

    suspend fun loadBlob(chunkId: Long): ByteArray? = transaction { chunks.loadBlob(chunkId) }

    @Transaction
    suspend fun setExportUri(sessionId: Long, uri: String) = transaction {
        sessions.setExportUri(sessionId, uri)
    }

    @Transaction
    suspend fun markAnalysisFailed(jobId: Long, error: String) = transaction {
        jobs.markFailed(jobId, error)
    }

    fun workChanges(): Flow<Set<String>> = db.invalidationTracker.createFlow(
        "audio_chunks", "analysis_jobs", "statements", "sessions", "annotations", "speaker_mappings")

    suspend fun hasPending(sessionId: Long): Boolean = transaction {
        chunks.unfinishedCount(sessionId) > 0 || jobs.unfinishedCount(sessionId) > 0
    }

    suspend fun segmentationAttempt(id: Long): Int = transaction {
        chunks.bumpAttempt(id)
        requireNotNull(chunks.get(id)).attemptCount
    }

    suspend fun requeueChunk(id: Long, error: String) = transaction { chunks.requeue(id, error) }
    suspend fun requeueAnalysis(id: Long, error: String) = transaction { jobs.requeue(id, error) }
    suspend fun forceFailPending(sessionId: Long) = transaction {
        chunks.bySession(sessionId).filter { it.state !in listOf(ChunkState.DISCARDED, ChunkState.FAILED) }
            .forEach { chunks.markFailedPreservingAudio(it.id, "Force finalized") }
        jobs.bySession(sessionId).filter { it.state in listOf(JobState.PENDING, JobState.IN_FLIGHT) }
            .forEach { jobs.markFailed(it.id, "Force finalized") }
    }
}
