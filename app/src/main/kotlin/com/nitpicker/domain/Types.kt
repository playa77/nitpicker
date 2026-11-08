package com.nitpicker.domain

import com.nitpicker.data.db.*
import com.nitpicker.llm.SegmentedStatement

const val SAMPLE_RATE_HZ = 16000
const val ANALYSIS_MODEL = "google/gemini-3.1-flash-lite"
const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
const val MIN_SPEECH_MERGE_MS = 1200
const val ROLLING_WINDOW_STATEMENTS = 20
const val CLAIMS_DIGEST_CAP = 80
const val RETAINED_SESSIONS_KEEP = 10
const val LOG_FILE_CAP = 512 * 1024

enum class Language {
    DE,
    EN,
}

enum class SessionStatus {
    ACTIVE,
    STOPPING,
    COMPLETED,
    FAILED,
}

enum class StatementState {
    PROVISIONAL,
    FINAL,
}

enum class ChunkState {
    CAPTURED,
    ASR_PENDING,
    ASR_DONE,
    ANALYZED,
    DISCARDED,
    FAILED,
}

enum class JobState {
    PENDING,
    IN_FLIGHT,
    DONE,
    FAILED,
}

enum class AnalysisJobReason {
    CHUNK,
    REANALYSIS,
}

enum class AnnotationFamily {
    FACT_CHECK,
    LOGIC_RHETORIC,
}

enum class NetworkState {
    ONLINE,
    OFFLINE,
}

data class SessionParams(
    val language: Language,
    val asrModel: String,
    val retentionMinutes: Int,
    val chunkMaxSec: Int,
    val vadSilenceMs: Int,
)

data class SettingsSnapshot(
    val asrModel: String = "openai/whisper-large-v3-turbo",
    val retentionMinutes: Int = 10,
    val chunkMaxSec: Int = 8,
    val vadSilenceMs: Int = 900,
)

data class PcmFrame(val pcm: ShortArray, val samples: Int, val atOffsetMs: Long)

data class AudioChunkData(
    val sessionId: Long,
    val seq: Long,
    val pcm16Le: ByteArray,
    val sampleRateHz: Int = SAMPLE_RATE_HZ,
    val startedAtOffsetMs: Long,
    val durationMs: Long,
)

data class TranscriptResult(val chunkId: Long, val text: String)

data class SegmentationResult(val chunkId: Long, val statements: List<SegmentedStatement>)

data class AnalysisResult(
    val jobId: Long,
    val annotations: List<AnalysisAnnotationSpec>,
    val claims: List<String>,
)

data class AnalysisAnnotationSpec(
    val statementId: Long,
    val seq: Long,
    val family: AnnotationFamily,
    val subType: String,
    val note: String,
    val quote: String?,
)

data class AnalysisJobContext(
    val job: AnalysisJobRow,
    val targets: List<StatementRow>,
    val window: List<StatementRow>,
    val digest: List<ClaimRow>,
    val language: Language,
)

data class PipelineSnapshot(
    val networkState: NetworkState = NetworkState.OFFLINE,
    val sessionState: SessionStatus? = null,
    val sessionClockMs: Long = 0,
    val asrQueueDepth: Int = 0,
    val analysisQueueDepth: Int = 0,
    val lastAsrLatencyMs: Long? = null,
    val lastAnalysisLatencyMs: Long? = null,
    val asrCalls: Int = 0,
    val llmCalls: Int = 0,
    val asrFailures: Int = 0,
    val llmFailures: Int = 0,
)

data class ExportOutcome(
    val sessionId: Long,
    val uri: String,
    val statementCount: Int,
    val annotationCount: Int,
)

data class SegmentationApplied(val createdIds: List<Long>, val extendedIds: List<Long>)

data class ExportModel(
    val session: SessionRow,
    val statements: List<StatementRow>,
    val annotations: List<AnnotationRow>,
    val mappings: List<SpeakerMappingRow>,
    val chunks: List<AudioChunkRow>,
)
