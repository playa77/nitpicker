package com.nitpicker.data.db

import androidx.room.*
import com.nitpicker.domain.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "sessions")
data class SessionRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long? = null,
    val language: Language,
    val status: SessionStatus = SessionStatus.ACTIVE,
    @ColumnInfo(name = "asr_model") val asrModel: String,
    @ColumnInfo(name = "retention_minutes") val retentionMinutes: Int,
    @ColumnInfo(name = "chunk_max_sec") val chunkMaxSec: Int,
    @ColumnInfo(name = "vad_silence_ms") val vadSilenceMs: Int,
    @ColumnInfo(name = "export_uri") val exportUri: String? = null,
)

@Entity(
    tableName = "audio_chunks",
    foreignKeys =
        [
            ForeignKey(
                entity = SessionRow::class,
                parentColumns = ["id"],
                childColumns = ["session_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices =
        [
            Index(value = ["session_id", "seq"], unique = true),
            Index(value = ["session_id", "state", "seq"], name = "idx_chunks_queue"),
        ],
)
data class AudioChunkRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val seq: Long,
    @ColumnInfo(name = "started_at_offset_ms") val startedAtOffsetMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "audio_blob") val audioBlob: ByteArray? = null,
    @ColumnInfo(name = "retained_path") val retainedPath: String? = null,
    val state: ChunkState = ChunkState.CAPTURED,
    val transcript: String? = null,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

@Entity(
    tableName = "statements",
    foreignKeys =
        [
            ForeignKey(
                entity = SessionRow::class,
                parentColumns = ["id"],
                childColumns = ["session_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["session_id", "seq"], unique = true)],
)
data class StatementRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    val seq: Long,
    @ColumnInfo(name = "speaker_tag") val speakerTag: String?,
    val text: String,
    @ColumnInfo(name = "started_at_offset_ms") val startedAtOffsetMs: Long,
    @ColumnInfo(name = "ended_at_offset_ms") val endedAtOffsetMs: Long?,
    val state: StatementState,
    @ColumnInfo(name = "source_chunk_ids") val sourceChunkIds: List<Long>,
)

@Entity(
    tableName = "annotations",
    foreignKeys =
        [
            ForeignKey(
                entity = StatementRow::class,
                parentColumns = ["id"],
                childColumns = ["statement_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["statement_id"], name = "idx_annotations_stmt")],
)
data class AnnotationRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "statement_id") val statementId: Long,
    val family: AnnotationFamily,
    @ColumnInfo(name = "sub_type") val subType: String,
    val note: String,
    val quote: String?,
    @ColumnInfo(name = "created_at_offset_ms") val createdAtOffsetMs: Long,
)

@Entity(
    tableName = "claims",
    foreignKeys =
        [
            ForeignKey(
                entity = SessionRow::class,
                parentColumns = ["id"],
                childColumns = ["session_id"],
                onDelete = ForeignKey.CASCADE,
            ),
            ForeignKey(
                entity = StatementRow::class,
                parentColumns = ["id"],
                childColumns = ["statement_id"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("session_id"), Index("statement_id")],
)
data class ClaimRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "statement_id") val statementId: Long,
    @ColumnInfo(name = "compact_text") val compactText: String,
    @ColumnInfo(name = "created_at_offset_ms") val createdAtOffsetMs: Long,
)

@Entity(
    tableName = "speaker_mappings",
    primaryKeys = ["session_id", "raw_tag"],
    foreignKeys =
        [
            ForeignKey(
                entity = SessionRow::class,
                parentColumns = ["id"],
                childColumns = ["session_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
)
data class SpeakerMappingRow(
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "raw_tag") val rawTag: String,
    @ColumnInfo(name = "display_name") val displayName: String? = null,
    @ColumnInfo(name = "merged_into_tag") val mergedIntoTag: String? = null,
)

@Entity(
    tableName = "analysis_jobs",
    foreignKeys =
        [
            ForeignKey(
                entity = SessionRow::class,
                parentColumns = ["id"],
                childColumns = ["session_id"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index(value = ["session_id", "state", "id"], name = "idx_jobs_queue")],
)
data class AnalysisJobRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "chunk_seq") val chunkSeq: Long?,
    @ColumnInfo(name = "target_statement_ids") val targetStatementIds: List<Long>,
    val reason: AnalysisJobReason,
    val state: JobState = JobState.PENDING,
    @ColumnInfo(name = "attempt_count", defaultValue = "0") val attemptCount: Int = 0,
    @ColumnInfo(name = "last_error") val lastError: String? = null,
)

@DatabaseView(
    "SELECT c.* FROM audio_chunks c WHERE c.state='ASR_DONE' AND NOT EXISTS (SELECT 1 FROM analysis_jobs j WHERE j.session_id=c.session_id AND j.reason='CHUNK' AND j.chunk_seq=c.seq)",
    viewName = "segmentation_pending",
)
data class SegmentationPending(@Embedded val chunk: AudioChunkRow)

class DbConverters {
    @TypeConverter fun idsToJson(v: List<Long>): String = Json.encodeToString(v)

    @TypeConverter fun jsonToIds(v: String): List<Long> = Json.decodeFromString(v)
}
