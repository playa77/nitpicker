# Document 2 — Technical Specification

**Title:** Nitpicker — Technical Specification
**Version:** 1.2.0
**Date:** 2026-11-08
**Status:** Approved — endpoint revision explicitly authorized by the operator on 2026-11-08 (supersedes v1.1.0)
**Baseline:** Doc 1 v1.1.0 (Design Document). Document 3 v1.2.0 (Implementation Roadmap) is re-audited against this v1.2.0. Cross-references cite "Doc 1 §x".
**Prepared per:** Multiphase Interview Tech Docs workflow v1.2.0.

**Revision History**

| Version | Date | Changes |
|---|---|---|
| 1.0.0 | 2026-10-04 | Initial draft, specifying Doc 1 v1.0.0 in full. |
| 1.1.0 | 2026-10-04 | Type/file closure fixes discovered during Doc 3 composition: §8.0 gains `SettingsSnapshot` and §4.1 specifies `SettingsStore.snapshot()`; §8.13 gains `SessionLog.deleteForSession()`; §2 project tree gains `ui/MainActivity.kt` and `ui/navigation/AppNav.kt`; §8.7 prompt source listings cleaned of string-escaping artifacts (no prompt-text change). No behavioral or capability changes. |

| 1.2.0 | 2026-11-08 | Operator-authorized correction after live API verification: ASR uses multipart `/audio/transcriptions` with binary WAV, ISO language and optional verbatim prompt; add `postTranscription` and typed request/response contracts. Chat completions remain the segmentation/analysis route. Update S7, T2, client and harness contracts. Provider guardrails remain an external access prerequisite. |

---

## 1. Implementation Scope

**In scope** — everything required by Doc 1 §2, nothing beyond:

1. Single-Gradle-module Android app (`:app`), native Kotlin + Jetpack Compose, `minSdk 30`, sideloaded APK (R-N3, R-N4).
2. Audio pipeline: `AudioRecord` capture, energy-VAD chunking (non-ML DSP, R-N2), WAV encoding, durable queues, OpenRouter ASR (R-F1), buffer-and-backfill (R-F12).
3. Two-stage LLM pipeline on `google/gemini-3.1-flash-lite`: statement segmentation (R-F2, R-F3, PDD-1) and combined analysis — fact-checking and logical/rhetorical families with exactly equal standing in one call (R-F4, R-F5, R-F6, R-F7).
4. Session lifecycle UX: De/En toggle, manual start/stop, screen-awake live protocol, session list, session detail with speaker rename/merge and re-export, settings (R-F9, R-F13).
5. Data: Room DB single source of truth; per-session Markdown export via MediaStore to a user-accessible folder (R-F10); audio retention facility — first N minutes (default 10, 0–10 configurable), exact upload bytes, app-private, prune-to-10-sessions, designed end-state removal (R-F11, D-9).
6. Debug/observability facility (status panel, per-session local log) — temporary, removed with the retention facility (A-5).
7. Test suite per §12; on-device acceptance procedure per §14.

**Out of scope:** all Doc 1 §2.3 non-goals; no server components; no telemetry.

---

## 2. Project Structure

```
Nitpicker/
├── settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml
├── keystore.properties                        (git-ignored; release signing)
└── app/
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml
        │   └── kotlin/com/nitpicker/
        │       ├── CompanionApp.kt             Application; constructs AppContainer
        │       ├── di/AppContainer.kt          composition root (§15)
        │       ├── domain/Types.kt             all shared domain types (§8.0)
        │       ├── data/
        │       │   ├── db/Entities.kt          Room entities (§5.1)
        │       │   ├── db/Daos.kt              Room DAOs (§5.2)
        │       │   ├── db/AppDatabase.kt
        │       │   ├── repository/SessionRepository.kt
        │       │   └── settings/SettingsStore.kt   DataStore + Keystore (§4, §7)
        │       ├── audio/
        │       │   ├── AudioCaptureEngine.kt
        │       │   ├── Chunker.kt              + Vad (parameters object)
        │       │   ├── WavEncoder.kt
        │       │   └── AudioRetentionStore.kt
        │       ├── pipeline/
        │       │   ├── PipelineOrchestrator.kt asr/segmentation/analysis workers
        │       │   ├── ConnectivityMonitor.kt  interface NetworkGate + impl
        │       │   └── PipelineMetrics.kt
        │       ├── llm/
        │       │   ├── ChatTransport.kt        interface
        │       │   ├── OpenRouterGateway.kt    impl: OkHttp + retry policy
        │       │   ├── ChatModels.kt           request/response DTOs
        │       │   ├── AsrClient.kt
        │       │   ├── StatementProcessor.kt
        │       │   ├── AnalysisEngine.kt
        │       │   └── LlmJsonParser.kt
        │       ├── prompts/Prompts.kt          full prompt contracts (§8.7)
        │       ├── export/
        │       │   ├── MarkdownExporter.kt
        │       │   └── DocumentSink.kt         interface + MediaStoreDocumentsSink impl
        │       ├── service/SessionService.kt   foreground service, owns orchestrator
        │       ├── log/SessionLog.kt
        │       └── ui/
        │           ├── MainActivity.kt          Compose host; mounts AppNav
        │           ├── navigation/AppNav.kt     nav graph: Start/Live/List/Detail/Settings
        │           ├── StartScreen.kt + StartViewModel.kt
        │           ├── LiveScreen.kt  + LiveViewModel.kt
        │           ├── ListScreen.kt  + ListViewModel.kt
        │           ├── DetailScreen.kt+ DetailViewModel.kt
        │           ├── SettingsScreen.kt + SettingsViewModel.kt
        │           └── common/StatusPanel.kt
        └── test/kotlin/com/nitpicker/
            ├── harness/PipelineHarness.kt      real components + scripted transport
            ├── fakes/ScriptedChatTransport.kt, FakeNetworkGate.kt, InMemoryDocumentSink.kt
            └── … test classes per §12
```

---

## 3. Dependencies

| Dependency | Floor | Purpose |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.0 | language |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.8 | workers, flows |
| `androidx.compose.*` (BOM) | 2024.09 | UI (Doc 1 D-11) |
| `androidx.activity:activity-compose`, `androidx.lifecycle:lifecycle-viewmodel-compose` | current stable at pin time | UI glue |
| `androidx.room:room-runtime/-ktx/room-compiler` | 2.6 | persistence (KSP) |
| `androidx.datastore:datastore-preferences` | 1.1 | settings |
| `com.squareup.okhttp3:okhttp` | 4.12 | OpenRouter HTTP |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7 | DTOs, LLM JSON |
| `androidx.core:core-ktx` | 1.13 | ServiceCompat FGS typing |
| Test: `junit:junit` 4.13.2, `org.robolectric:robolectric` 4.13, `com.squareup.okhttp3:mockwebserver` 4.12, `kotlinx-coroutines-test` 1.8, `androidx.room:room-testing` 2.6 | | §12 |

**Rule:** exact versions are pinned once, in `gradle/libs.versions.toml`, at the first commit (Doc 3 WP-01); floors above are compatibility minimums, not placeholders. No other third-party dependencies are permitted; any addition requires a Doc 2 revision (no invented services — workflow gate).

---

## 4. Configuration

### 4.1 User settings (SettingsStore, DataStore; edited via SettingsScreen)

| Key | Type | Default | Range | Traces |
|---|---|---|---|---|
| `asr_model` | String | `openai/whisper-large-v3-turbo` | {`openai/whisper-large-v3-turbo`, `microsoft/mai-transcribe-2`} | R-F1 |
| `retention_minutes` | Int | 10 | 0–10 (0 = discard-immediately) | R-F11, PDD-3 |
| `chunk_max_sec` | Int | 8 | 5–15 | R-N8 lever |
| `vad_silence_ms` | Int | 900 | 400–2000 | D-4 |
| `api_key_cipher` / `api_key_iv` | Base64 String | unset | — | §7 |

`SettingsStore` public surface (the typed producer exists so workers never read raw preferences):

```kotlin
class SettingsStore(context: Context) {
    val apiKeySet: Flow<Boolean>        // gates Start (§8.14)
    fun apiKey(): String?               // decrypted; in-memory only
    fun snapshot(): SettingsSnapshot    // typed view of the keys above
                                        // callers: orchestrator workers → AudioRetentionStore (§8.4, §8.9)
    suspend fun setApiKey(v: String)
    suspend fun setAsrModel(v: String)
    suspend fun setRetentionMinutes(v: Int)
    suspend fun setChunkMaxSec(v: Int)
    suspend fun setVadSilenceMs(v: Int)
}
```

### 4.2 Fixed constants (not user-editable)

| Constant | Value | Traces |
|---|---|---|
| `ANALYSIS_MODEL` | `google/gemini-3.1-flash-lite` | CA-3 |
| `OPENROUTER_BASE_URL` | `https://openrouter.ai/api/v1` (constructor-injectable for tests via `GatewayConfig`) | R-N1 |
| `SAMPLE_RATE_HZ` / channels / depth | 16000 / mono / 16-bit PCM | D-7 |
| `VAD_FRAME_MS` | 20 | D-4 |
| `MIN_SPEECH_MERGE_MS` | 1200 | §8.2 |
| `ROLLING_WINDOW_STATEMENTS` | 20 | D-3 |
| `CLAIMS_DIGEST_CAP` | 80, drop-oldest | D-3 |
| `RETRY_POLICY` | 3 attempts, backoff 2 s / 8 s / 32 s, ±20 % jitter; 429 honors `Retry-After` (cap 60 s) | §11 |
| `HTTP_TIMEOUTS` | connect 10 s, write 30 s, read 120 s | §11 |
| `RETAINED_SESSIONS_KEEP` | 10 | PDD-4 |
| `EXPORT_DIR` | MediaStore Documents, relative path `Nitpicker/` | R-F10 |
| `LOG_FILE_CAP` | 512 KB per session, head-truncated | §10 |

---

## 5. Data Layer

Room database `app_database` (version 1, `exportSchema = true`). All timestamps are epoch-ms UTC; per-statement offsets are session-relative ms.

### 5.1 DDL

```sql
CREATE TABLE sessions (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  started_at INTEGER NOT NULL,
  ended_at INTEGER,
  language TEXT NOT NULL CHECK (language IN ('DE','EN')),
  status TEXT NOT NULL CHECK (status IN ('ACTIVE','STOPPING','COMPLETED','FAILED')),
  asr_model TEXT NOT NULL,
  retention_minutes INTEGER NOT NULL,
  chunk_max_sec INTEGER NOT NULL,
  vad_silence_ms INTEGER NOT NULL,
  export_uri TEXT
);

CREATE TABLE audio_chunks (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,
  started_at_offset_ms INTEGER NOT NULL,
  duration_ms INTEGER NOT NULL,
  audio_blob BLOB,                       -- WAV bytes; present CAPTURED→terminal; [REFINES Doc 1 §5 uploadBytes]
  retained_path TEXT,                    -- set when moved to AudioRetentionStore file
  state TEXT NOT NULL CHECK (state IN
    ('CAPTURED','ASR_PENDING','ASR_DONE','ANALYZED','DISCARDED','FAILED')),
  transcript TEXT,
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  UNIQUE (session_id, seq)
);
CREATE INDEX idx_chunks_queue ON audio_chunks(session_id, state, seq);

CREATE TABLE statements (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  seq INTEGER NOT NULL,                  -- per-session monotonic
  speaker_tag TEXT,                      -- inferred, e.g. 'S2'; never overwritten (D-5)
  text TEXT NOT NULL,
  started_at_offset_ms INTEGER NOT NULL,
  ended_at_offset_ms INTEGER,
  state TEXT NOT NULL CHECK (state IN ('PROVISIONAL','FINAL')),
  source_chunk_ids TEXT NOT NULL,        -- JSON int array
  UNIQUE (session_id, seq)
);

CREATE TABLE annotations (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  statement_id INTEGER NOT NULL REFERENCES statements(id) ON DELETE CASCADE,
  family TEXT NOT NULL CHECK (family IN ('FACT_CHECK','LOGIC_RHETORIC')),
  sub_type TEXT NOT NULL,
  note TEXT NOT NULL,
  quote TEXT,
  created_at_offset_ms INTEGER NOT NULL
);
CREATE INDEX idx_annotations_stmt ON annotations(statement_id);

CREATE TABLE claims (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  statement_id INTEGER NOT NULL REFERENCES statements(id) ON DELETE CASCADE,
  compact_text TEXT NOT NULL,
  created_at_offset_ms INTEGER NOT NULL
);

CREATE TABLE speaker_mappings (
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  raw_tag TEXT NOT NULL,
  display_name TEXT,
  merged_into_tag TEXT,
  PRIMARY KEY (session_id, raw_tag)
);

CREATE TABLE analysis_jobs (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  session_id INTEGER NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
  chunk_seq INTEGER,                     -- null for REANALYSIS
  target_statement_ids TEXT NOT NULL,    -- JSON long array; text loaded fresh at run time
  reason TEXT NOT NULL CHECK (reason IN ('CHUNK','REANALYSIS')),
  state TEXT NOT NULL CHECK (state IN ('PENDING','IN_FLIGHT','DONE','FAILED')),
  attempt_count INTEGER NOT NULL DEFAULT 0,
  last_error TEXT
);
CREATE INDEX idx_jobs_queue ON analysis_jobs(session_id, state, id);
```

**Durability semantics:** `audio_chunks` rows with state `CAPTURED` **are** the ASR queue; `analysis_jobs` rows with state `PENDING` are the analysis queue (Doc 1 §3.3 durable FIFOs). `audio_blob` is present from enqueue until terminal transition; it is nulled at `DISCARDED`/`FAILED` — except when moved to a retention file within the retention window (§8.4). The queue tables therefore carry their own payloads across process death — state survives the process boundary by construction.

### 5.2 DAOs (signatures; only SessionRepository may call these)

```kotlin
@Dao interface SessionDao {
    fun insert(s: SessionRow): Long
    @Transaction fun setStatus(id: Long, status: SessionStatus, endedAt: Long?)
    fun setExportUri(id: Long, uri: String)
    fun getById(id: Long): SessionRow?
    fun activeOrStopping(): List<SessionRow>
    fun sessionsFlow(): Flow<List<SessionRow>>
}
@Dao interface ChunkDao {
    fun insert(c: AudioChunkRow): Long
    fun claimNextForAsr(sessionId: Long): AudioChunkRow?      -- txn: CAPTURED→ASR_PENDING, oldest seq
    fun resetInFlightToCaptured(sessionId: Long)
    fun saveTranscript(id: Long, transcript: String)          -- txn: →ASR_DONE
    fun markFailed(id: Long, error: String)                   -- txn: →FAILED
    fun claimNextForSegmentation(sessionId: Long): AudioChunkRow?  -- txn: ASR_DONE→(claimed via seg claim marker)*
    fun markAnalyzed(id: Long); fun markDiscarded(id: Long); fun nullBlob(id: Long)
    fun pendingAsrCountFlow(sessionId: Long): Flow<Int>
    fun bySession(sessionId: Long): List<AudioChunkRow>
    fun loadBlob(id: Long): ByteArray?
}
@Dao interface StatementDao {
    fun insertAll(rows: List<StatementRow>): List<Long>
    fun lastStatement(sessionId: Long): StatementRow?
    fun extendStatement(id: Long, appendText: String, endOffsetMs: Long, chunkId: Long)
    fun finalizeIfProvisional(id: Long)
    fun finalizeAllProvisional(sessionId: Long)
    fun statementsFlow(sessionId: Long): Flow<List<StatementRow>>
    fun byIds(ids: List<Long>): List<StatementRow>
    fun windowBefore(sessionId: Long, beforeSeq: Long, limit: Int): List<StatementRow>
    fun recentTags(sessionId: Long, limit: Int): List<String>
}
@Dao interface AnnotationDao {
    fun insertAll(rows: List<AnnotationRow>)
    fun deleteForStatements(statementIds: List<Long>)
    fun annotationsForStatementsFlow(ids: List<Long>): Flow<List<AnnotationRow>>
    fun countForSession(sessionId: Long): Int
}
@Dao interface ClaimDao {
    fun insertAll(rows: List<ClaimRow>); fun digest(sessionId: Long): List<ClaimRow>
    fun deleteOldestBeyond(sessionId: Long, cap: Int)
}
@Dao interface SpeakerMappingDao {
    fun upsert(m: SpeakerMappingRow); fun forSessionFlow(sessionId: Long): Flow<List<SpeakerMappingRow>>
}
@Dao interface AnalysisJobDao {
    fun insert(j: AnalysisJobRow): Long
    fun claimNextPending(sessionId: Long): AnalysisJobRow?   -- txn: PENDING→IN_FLIGHT, oldest id
    fun resetInFlightToPending(sessionId: Long)
    fun markDone(id: Long); fun markFailed(id: Long, error: String)
    fun pendingCountFlow(sessionId: Long): Flow<Int>
    fun existsDoneForStatement(statementId: Long): Boolean
}
```

\* **Segmentation claim marker:** segmentation work items are `audio_chunks` in state `ASR_DONE` that have no `analysis_jobs` row with `reason='CHUNK'` and `chunk_seq = seq` — expressed as a view `segmentation_pending` joined in the DAO query; claim transitions are idempotent (`UPDATE … WHERE state='ASR_DONE' AND NOT EXISTS(...)`), so a crash between claim and completion is re-claimable after `resetInFlightToCaptured`/service restart recovery (§8.9).

### 5.3 SessionRepository (the only DAO caller)

Constructor: `SessionRepository(db: AppDatabase)`. All mutating methods are `@Transaction`-wrapped and are the **production write path for every domain table** (this is what makes §12's test-construction rule enforceable). Public API (each maps 1:1 onto DAO operations above plus composition):

```kotlin
suspend fun createSession(params: SessionParams): Long
suspend fun enqueueChunk(sessionId: Long, chunk: AudioChunkData): Long        // insert CAPTURED + audio_blob
suspend fun claimNextChunkForAsr(sessionId: Long): AudioChunkRow?
suspend fun saveTranscript(result: TranscriptResult)                          // ASR_DONE
suspend fun markChunkFailed(chunkId: Long, error: String)
suspend fun claimNextChunkForSegmentation(sessionId: Long): AudioChunkRow?
suspend fun applySegmentation(chunkId: Long, sessionId: Long, r: SegmentationResult): SegmentationApplied
    // txn: continuation → extendStatement + enqueue REANALYSIS job if any job targeting
    // the statement exists or annotations exist; else insertAll statements
    // (PROVISIONAL unless endsProvisionally=false) + finalize prior PROVISIONAL
    // statement when first new statement has continuesPrevious=false;
    // always insert CHUNK analysis_job for this chunk's statements.
    // Returns: created/extended statement ids (for logging/metrics).
suspend fun claimNextAnalysisJob(sessionId: Long): AnalysisJobContext?
    // loads job + fresh target statements + rolling window + claims digest + language
suspend fun saveAnalysis(jobId: Long, sessionId: Long, result: AnalysisResult)
    // txn: deleteForStatements(targets) → insert annotations → append claims +
    // cap enforcement → markDone; then chunk(s) ANALYZED for CHUNK jobs
suspend fun finalizeSession(sessionId: Long)                                   // finalizeAllProvisional
suspend fun setStatus(sessionId: Long, status: SessionStatus)
suspend fun upsertMapping(m: SpeakerMappingRow)
suspend fun sessionForExport(sessionId: Long): ExportModel                      // full read model
suspend fun statementsFlow(sessionId: Long): Flow<List<StatementRow>>
suspend fun annotationsFlow(sessionId: Long, ids: List<Long>): Flow<List<AnnotationRow>>
suspend fun mappingsFlow(sessionId: Long): Flow<List<SpeakerMappingRow>>
suspend fun activeOrStopping(): List<SessionRow>
suspend fun sessionsFlow(): Flow<List<SessionRow>>
suspend fun deleteSession(id: Long)                                            // cascade + files via callers
suspend fun queueDepthsFlow(sessionId: Long): Flow<Pair<Int, Int>>
```

---

## 6. API Specification

### 6.1 Transport (OpenRouterGateway implements ChatTransport)

```kotlin
interface ChatTransport {
    suspend fun postChat(req: ChatRequest): ChatResponse
    suspend fun postTranscription(req: TranscriptionRequest): TranscriptionResponse
}
data class GatewayConfig(val baseUrl: String = OPENROUTER_BASE_URL,
                         val apiKeyProvider: () -> String,
                         val retry: RetryPolicy = RetryPolicy(), val timeouts: HttpTimeouts = HttpTimeouts())
class OpenRouterGateway(cfg: GatewayConfig) : ChatTransport   // OkHttp; throws typed errors (§11)
```

Two explicit operations share one OpenRouter gateway, credential provider, retry budget, timeouts and typed errors:

- `postChat`: `POST {baseUrl}/chat/completions`, `Content-Type: application/json`, for segmentation and analysis.
- `postTranscription`: `POST {baseUrl}/audio/transcriptions`, `Content-Type: multipart/form-data` with a generated boundary. Fields: `model`, `language` (`de` or `en`), optional `prompt`, and `file` (`chunk.wav`, `audio/wav`, exact stored WAV bytes). No base64 wrapper or chat message is sent.

Both send `Authorization: Bearer <key>`; optional attribution headers are `HTTP-Referer: nitpicker.local` and `X-Title: Nitpicker` (D-12). Redirects and hidden client retries are disabled. HTTP 404 caused by provider/data-policy/guardrail restrictions is a non-retryable routing/access error, not proof of an invalid API key. Do not switch models, bypass account policies, or change credentials automatically.

Live verification on 2026-11-08 established that both configured transcription models explicitly reject the chat endpoint. This replaces the former A-6 request-shape assumption with a dedicated audio endpoint contract.


### 6.2 DTOs (llm/ChatModels.kt)

```kotlin
@Serializable data class ChatRequest(val model: String, val messages: List<ChatMessage>)
@Serializable data class ChatMessage(val role: String, val content: JsonElement)
    // content = plain JSON string for segmentation/analysis; arrays are generic chat parts, not ASR
@Serializable data class TextPart(val type: String = "text", val text: String)
@Serializable data class AudioPart(val type: String = "input_audio",
                                   val input_audio: AudioData)
@Serializable data class AudioData(val data: String, val format: String = "wav")
@Serializable data class ChatResponse(val choices: List<Choice>, val usage: Usage?)
@Serializable data class Choice(val message: RespMessage)
@Serializable data class RespMessage(val content: String?)
@Serializable data class Usage(val total_tokens: Long?)
```

The audio endpoint has a separate contract (the request is a multipart envelope, not JSON):

```kotlin
data class TranscriptionRequest(val model: String, val audioWav: ByteArray,
    val language: Language, val prompt: String? = null)
@Serializable data class TranscriptionResponse(val text: String)
```

### 6.3 Call 1 — ASR (AsrClient)

Build `TranscriptionRequest(model = settings.asrModel, audioWav = chunk.audio_blob, language = session.language, prompt = Prompts.asrInstruction(language))` and call `transport.postTranscription`. The language is encoded as `de`/`en` in the multipart envelope; the optional prompt preserves the verbatim/no-translation instruction. The `file` payload is byte-identical to the persisted/retained WAV. Decode the response JSON `text` field; blank text → `MalformedLlmOutputException("empty transcript")`. Output remains `TranscriptResult(chunkId, text)`. No chat `choices` extraction, base64 encoding, or translation endpoint is used for ASR.

### 6.4 Call 2 — Segmentation (StatementProcessor)

Request: model = `ANALYSIS_MODEL`; system = `Prompts.segmentationSystem(language)`; user = JSON object `{ "transcript": "...", "previousStatementTail": "...", "recentSpeakerTags": ["S1","S2"], "language": "DE" }`. Response parsed by `LlmJsonParser` into:

```kotlin
@Serializable data class SegmentationPayload(
    val statements: List<SegmentedStatement>)
data class SegmentedStatement(val text: String, val continuesPrevious: Boolean,
                              val speakerTag: String?, val endsProvisionally: Boolean)
```

### 6.5 Call 3 — Analysis (AnalysisEngine)

Request: model = `ANALYSIS_MODEL`; system = `Prompts.analysisSystem(language)`; user = JSON object `{ "statements": [{ "seq": n, "text": "..." }], "recentWindow": [...], "claimsDigest": ["S1: ..."], "language": "DE" }`. Response parsed into:

```kotlin
@Serializable data class AnalysisPayload(
    val annotations: List<AnnotationSpec>, val claims: List<String>)
@Serializable data class AnnotationSpec(val statementSeq: Long, val family: String,
                                        val subType: String, val note: String, val quote: String? = null)
```

Parse validation: `family` must be `FACT_CHECK` or `LOGIC_RHETORIC` (else annotation dropped and counted as malformed); `statementSeq` must match a target statement (else dropped); if `quote` is present and not a verbatim substring of that statement's text → `quote` is dropped, `note` kept (never a silent text invention).

### 6.6 Error semantics (transport)

| Condition | Behavior |
|---|---|
| HTTP 429 | honor `Retry-After` (≤60 s), retry within `RETRY_POLICY` budget |
| HTTP 5xx / IOException | retry with backoff; exhausted → `OpenRouterHttpException`/`OpenRouterTimeoutException` to worker |
| HTTP 401/403 | no retry → `OpenRouterAuthException`; session continues capturing; status panel shows "auth error", queues hold (fixable by key update) |
| Other HTTP 4xx (including 404 provider/guardrail routing restrictions) | no retry → `OpenRouterHttpException(status)`; preserve the configured model and report the access restriction |
| 200, unparsable body | `MalformedLlmOutputException` |

---

## 7. AuthN / AuthZ

- **Single secret:** the OpenRouter API key. Entered in SettingsScreen; held in memory only as plaintext after decryption.
- **At rest:** `SettingsStore` encrypts the key with an AES-256-GCM key generated in the **Android Keystore** (`StrongBox` when available); ciphertext + IV in app-private DataStore. This is credential hygiene, not the declined at-rest encryption of meeting data (Doc 1 §7).
- **In transit:** TLS to OpenRouter only (R-N1).
- **AuthZ:** none — single-user device app; no accounts, no multi-user isolation. Android sandbox provides app-level isolation.
- **Permissions (manifest + runtime):** `RECORD_AUDIO` (runtime, requested in Start flow), `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` (runtime on 13+, requested alongside; denial hides the FGS notification but does not stop recording — spec'd behavior), `WAKE_LOCK`. FGS started with `FOREGROUND_SERVICE_TYPE_MICROPHONE` via `ServiceCompat.startForeground`.

---

## 8. Module Specifications

### 8.0 Shared domain types (domain/Types.kt)

```kotlin
enum class Language { DE, EN }
enum class SessionStatus { ACTIVE, STOPPING, COMPLETED, FAILED }
enum class StatementState { PROVISIONAL, FINAL }
enum class ChunkState { CAPTURED, ASR_PENDING, ASR_DONE, ANALYZED, DISCARDED, FAILED }
enum class JobState { PENDING, IN_FLIGHT, DONE, FAILED }
enum class AnalysisJobReason { CHUNK, REANALYSIS }
enum class AnnotationFamily { FACT_CHECK, LOGIC_RHETORIC }
enum class NetworkState { ONLINE, OFFLINE }

data class SessionParams(val language: Language, val asrModel: String,
    val retentionMinutes: Int, val chunkMaxSec: Int, val vadSilenceMs: Int)
data class SettingsSnapshot(val asrModel: String, val retentionMinutes: Int,
    val chunkMaxSec: Int, val vadSilenceMs: Int)
data class PcmFrame(val pcm: ShortArray, val samples: Int, val atOffsetMs: Long)
data class AudioChunkData(val sessionId: Long, val seq: Long, val pcm16Le: ByteArray,
    val sampleRateHz: Int = SAMPLE_RATE_HZ, val startedAtOffsetMs: Long, val durationMs: Long)
data class TranscriptResult(val chunkId: Long, val text: String)
data class SegmentationResult(val chunkId: Long, val statements: List<SegmentedStatement>)
data class AnalysisResult(val jobId: Long, val annotations: List<AnalysisAnnotationSpec>,
                          val claims: List<String>)
data class AnalysisAnnotationSpec(val statementId: Long, val seq: Long,
    val family: AnnotationFamily, val subType: String, val note: String, val quote: String?)
data class AnalysisJobContext(val job: AnalysisJobRow, val targets: List<StatementRow>,
    val window: List<StatementRow>, val digest: List<ClaimRow>, val language: Language)
data class PipelineSnapshot(val networkState: NetworkState, val sessionState: SessionStatus?,
    val sessionClockMs: Long, val asrQueueDepth: Int, val analysisQueueDepth: Int,
    val lastAsrLatencyMs: Long?, val lastAnalysisLatencyMs: Long?,
    val asrCalls: Int, val llmCalls: Int, val asrFailures: Int, val llmFailures: Int)
data class ExportOutcome(val sessionId: Long, val uri: String,
    val statementCount: Int, val annotationCount: Int)
data class SegmentationApplied(val createdIds: List<Long>, val extendedIds: List<Long>)
```

Every type named in any signature below is defined here or in §5/§6 — type closure verified in §16.

### 8.1 CompanionApp

Constructs `AppContainer` in `onCreate()`; exposes it via a `val container` property. No other initialization. **Caller of:** `AppContainer` constructor. **Called by:** Android framework.

### 8.2 AudioCaptureEngine

Constructs/owns `AudioRecord` (16 kHz mono, 16-bit, 320-sample/20 ms reads) and a `Chunker`; reads in a `runBlocking`-free loop on a dedicated `Dispatchers.IO` coroutine.

```kotlin
class AudioCaptureEngine(sessionId: Long, chunkMaxSec: Int,
                         private val onChunkClosed: suspend (AudioChunkData) -> Unit) {
    fun start(scope: CoroutineScope)      // starts read loop; throws MicLostException on fatal read errors
    fun stop()                            // drains Chunker tail into a final chunk, releases AudioRecord
}
```

`onChunkClosed` is wired by `SessionService` to: `wav = WavEncoder.toWav(chunk)` → `repository.enqueueChunk(sessionId, chunk.copy-WAV-form)` (the enqueue persists `audio_blob = wav`, so DB bytes are the upload bytes from the start) → `metrics.tickCapture()`. **VAD (Chunker, non-ML DSP — R-N2):** per 20 ms frame compute RMS; noise floor = EMA(α=0.05) of silent frames; speech if RMS > max(noiseFloor×8, fixed floor). Chunk closes after `vad_silence_ms` of continuous silence, or at `chunk_max_sec`; speech bursts < `MIN_SPEECH_MERGE_MS` merge into the following chunk (Doc 1 §3.2). Pseudocode:

```
state = SILENCE; buffer = []; speechMs = 0; silenceMs = 0
for frame in frames:
  buffer += frame
  if energy(frame) > threshold: state = SPEECH; speechMs += 20; silenceMs = 0
  else if state == SPEECH: silenceMs += 20
  if state == SPEECH and silenceMs >= vadSilenceMs: closeChunk(buffer); reset
  if bufferMs >= chunkMaxSec: closeChunk(buffer); reset
```
**Called by:** `SessionService`. **Calls:** `Chunker.offer(PcmFrame)` internally; `onChunkClosed` (wired). **Error:** read failure/route loss → `MicLostException` → session FAILED (§11).

### 8.3 WavEncoder

`object WavEncoder { fun toWav(chunk: AudioChunkData): ByteArray }` — 44-byte RIFF header + PCM payload. **Called by:** AudioCaptureEngine wiring (SessionService). Deterministic, pure — unit-tested.

### 8.4 AudioRetentionStore

```kotlin
class AudioRetentionStore(private val filesDir: File) {
    fun retainIfNeeded(sessionId: Long, chunkRow: AudioChunkRow, blob: ByteArray, cfg: SettingsSnapshot): File?
        // if chunkRow.startedAtOffsetMs < retentionMinutes*60_000 && retentionMinutes > 0:
        // write blob to filesDir/retained/session-{id}/chunk-{seq}.wav; return file
    fun prune(keepLastN: Int = RETAINED_SESSIONS_KEEP)   // delete retained/ dirs of all but newest N sessions
    fun deleteForSession(sessionId: Long)
    fun filesForSession(sessionId: Long): List<File>
}
```
**Called by:** analysis/asr workers (post-terminal blob handling, §8.9), `SessionService` (prune at session start), `DetailViewModel` (delete). **Behavior:** file write happens **after** the DB transaction that nulls nothing — order: write file, then `repository.markDiscarded/nullBlob`; crash between → blob remains in DB and the next terminal transition retries the move (idempotent by overwrite).

### 8.5 ChatTransport / OpenRouterGateway — §6. **Called by:** AsrClient, StatementProcessor, AnalysisEngine; tests via `ScriptedChatTransport` and MockWebServer.

### 8.6 AsrClient

```kotlin
class AsrClient(private val transport: ChatTransport, private val settings: SettingsStore) {
    suspend fun transcribe(chunk: AudioChunkRow): TranscriptResult
}
```
Builds request per §6.3 with `Prompts.asrInstruction(language)`; returns transcript. Latency measured around `transport.postTranscription` and reported via callback `onLatency: (Long) -> Unit` (wired to PipelineMetrics). **Called by:** PipelineOrchestrator.asrWorker.

### 8.7 Prompts (prompts/Prompts.kt — full contract text; the equal-standing requirement is enforced here, R-F6)

```kotlin
object Prompts {
    fun asrInstruction(language: Language): String =
        "Transcribe this audio verbatim. The conversation language is ${language.name}. " +
        "Include every speaker's words exactly as spoken. Do not translate. Do not summarize. " +
        "Do not add commentary or labels."

    fun segmentationSystem(language: Language): String = """
        You convert a raw transcript chunk from a ${language} business meeting into discrete statements.
        Rules:
        - Keep wording verbatim; no paraphrasing, no corrections.
        - Respond in the same language as the transcript.
        - The chunk may start mid-statement: if it continues the provided previous statement,
          set continuesPrevious=true and give only the continuation text.
        - Infer a speaker tag (S1, S2, ...) only from conversational cues (vocatives, turn-taking,
          content); use the provided recentSpeakerTags for consistency. If not inferable, use null.
        - endsProvisionally=true only if the chunk appears cut off mid-statement.
        Return ONLY valid JSON: {"statements":[{"text":"...","continuesPrevious":bool,
        "speakerTag":"S2"|null,"endsProvisionally":bool}]}
    """.trimIndent()

    fun analysisSystem(language: Language): String = """
        You analyze statements from a ${language} business meeting. You perform TWO tasks of
        exactly equal priority — never favor one over the other:
        1. FACT_CHECK: identify factual claims (numbers, dates, quantities, names, assertions of
           fact) and verify them against your internal knowledge only. You have no web access and
           must not speculate about external sources. Also check claims against the provided
           claimsDigest for contradictions within this meeting.
        2. LOGIC_RHETORIC: identify logical errors, imprecisions, false conclusions, false
           assumptions, and rhetorical figures or tricks.
        Rules:
        - Annotate ONLY when warranted; empty annotation lists are valid and often correct.
        - Phrase every note as an observation, never a verdict ("The figure X conflicts with Y",
           not "The speaker is wrong").
        - Write notes in ${language}. Keep notes under 240 characters.
        - quote must be a verbatim substring of the statement text.
        - subType is a short label, e.g. "numeric-mismatch", "false-conclusion",
          "false-assumption", "imprecision", "rhetorical-figure", "inconsistency-with-earlier".
        - Extract checkable factual claims into claimsDigest format: one line, e.g.
          "S1: Q3 revenue EUR 4.2m". Only checkable assertions, no opinions.
        Return ONLY valid JSON: {"annotations":[{"statementSeq":n,"family":"FACT_CHECK"|
        "LOGIC_RHETORIC","subType":"...","note":"...","quote":"..."}],"claims":["..."]}
    """.trimIndent()
}
```
**Called by:** AsrClient, StatementProcessor, AnalysisEngine. **No caller-invisible variants:** these three functions are the only prompt entry points.

### 8.8 StatementProcessor

```kotlin
class StatementProcessor(private val transport: ChatTransport) {
    suspend fun segment(chunk: AudioChunkRow, tail: String?, recentTags: List<String>,
                        language: Language): SegmentationResult
}
```
Builds request per §6.4, parses via `LlmJsonParser.parse<SegmentationPayload>`; on parse failure sends one repair re-prompt (assistant content + user "Your previous response was not valid JSON matching the schema. Return only corrected JSON."), second failure → `MalformedLlmOutputException`. **Called by:** PipelineOrchestrator.segmentationWorker.

### 8.9 PipelineOrchestrator (in SessionService scope)

```kotlin
class PipelineOrchestrator(private val repo: SessionRepository,
    private val asr: AsrClient, private val seg: StatementProcessor,
    private val ana: AnalysisEngine, private val retention: AudioRetentionStore,
    private val gate: ConnectivityMonitor, private val metrics: PipelineMetrics,
    private val log: SessionLog, private val settings: SettingsStore) {
    fun startWorkers(sessionId: Long, scope: CoroutineScope)   // launches 3 worker coroutines
    fun recoverInFlight(sessionId: Long)   // resetInFlightToCaptured / resetInFlightToPending at start
    val drainComplete: StateFlow<Boolean>
}
```

Workers (all observe DAO flows for work, all gated on `gate.state == ONLINE` before any network call; on OFFLINE they park — queues accumulate — R-F12):

- **asrWorker:** loop { `chunk = repo.claimNextChunkForAsr` ?: idle } → `asr.transcribe(chunk)` → `repo.saveTranscript`. On typed failure: `attempt_count++`; if <3 re-queue (state back to CAPTURED, backoff); else `repo.markChunkFailed` + `retention.retainIfNeeded(..., settings.snapshot())` (in-window, for debug) + `nullBlob`.
- **segmentationWorker:** loop { `chunk = repo.claimNextChunkForSegmentation` } → load tail context (`repo` last statement text + `recentTags(6)`) → `seg.segment(...)` → `repo.applySegmentation` (creates CHUNK analysis job). Same retry policy; on terminal failure the chunk's statements never appear — export shows `[transcription gap mm:ss]` for FAILED chunks (§8.11).
- **analysisWorker:** loop { `ctx = repo.claimNextAnalysisJob` } → `ana.analyze(ctx)` → `repo.saveAnalysis` (delete+replace for REANALYSIS targets) → for CHUNK jobs: chunk → ANALYZED, then `retention.retainIfNeeded(..., settings.snapshot())` + `markDiscarded` + `nullBlob` per §8.4 order. **Continuation re-analysis:** `applySegmentation` always enqueues a REANALYSIS job when extending a statement — the extended statement's annotations are wholesale replaced when that job completes (bounded cost: one extra flash-lite call per consolidated statement; justification: correctness of annotation-to-final-text attachment outweighs the marginal call cost — [COMPLEXITY JUSTIFICATION, refines Doc 1 D-6]).

`drainComplete` = (asrQueue empty) ∧ (analysisQueue empty) ∧ (segmentation pending empty); drives the STOPPING→export transition (§8.10).

**Called by:** SessionService. **Calls:** AsrClient, StatementProcessor, AnalysisEngine, SessionRepository, AudioRetentionStore, ConnectivityMonitor, PipelineMetrics, SessionLog, SettingsStore. **In-flight recovery:** `recoverInFlight` runs at every service start and after process death (workers re-claim).

### 8.10 SessionService (foreground, `foregroundServiceType="microphone"`)

```kotlin
class SessionService : Service() {
    // Actions: ACTION_START(language), ACTION_STOP, ACTION_RESUME(sessionId), ACTION_FORCE_FINALIZE
    fun start(language: Language)          // via startForegroundService Intent from StartViewModel
    fun stop()                              // ACTIVE→STOPPING; capture stops; workers drain
    fun resume(sessionId: Long)             // recovery: re-init capture; workers pick up DB queues
    fun forceFinalize()                     // export partial; mark remaining FAILED
}
```
Owns: partial wake lock + `WifiLock(WIFI_MODE_FULL_HIGH_PERF)` [PROPOSED — drop to default mode if battery cost outweighs latency benefit], notification channel (silenced — [CONFLICT-1] resolution), `PipelineOrchestrator`, `AudioCaptureEngine` wiring. **Stop path:** `stop()` → repo.setStatus(STOPPING) → capture stop → when `drainComplete` → `repo.finalizeSession` → `MarkdownExporter.export(sessionId)` → repo.setStatus(COMPLETED) + `setExportUri`. If drain stalls >120 s, LiveScreen shows a **Force finalize** button (manual, deterministic — no hidden auto-timeout). **Death recovery:** DB is source of truth; `ListViewModel` surfaces ACTIVE/STOPPING sessions with a **Recover** action → `resume()`; time between death and recovery is recorded in export as `[recording gap mm:ss–mm:ss]`. **Called by:** UI ViewModels via Intents. **Calls:** everything above + MarkdownExporter + AudioRetentionStore.prune at start.

### 8.11 MarkdownExporter

```kotlin
class MarkdownExporter(private val repo: SessionRepository, private val sink: DocumentSink) {
    suspend fun export(sessionId: Long): ExportOutcome
}
interface DocumentSink { suspend fun write(fileName: String, content: String): String /* uri */ }
```
Reads `repo.sessionForExport` (production read path), applies `speaker_mappings` (display name, `?` suffix for unmapped inferred tags; merged tags resolve one level), renders:

```markdown
# Meeting Protocol — 2026-10-04 14:30 (DE)
- Started: 14:30:12 · Ended: 15:47:03 · Duration: 77 min
- Language: DE · ASR: openai/whisper-large-v3-turbo · Statements: 214 · Annotations: 61

**[00:03:41] S2?** Wir hatten im Q3 einen Umsatz von 4,2 Millionen Euro.
*Fact-check: The published Q3 figure was 3.9m EUR (internal knowledge); possible mismatch.*
*Logic: Imprecision — "Umsatz" vs. revenue before/after rebates not specified.*

**[00:04:15] S1:** …
```
FAILED-chunk gaps render as `[transcription gap mm:ss]`; recording gaps as `[recording gap …]`. Filename: `protocol-YYYY-MM-DD-HHmm-{DE|EN}.md`. `DocumentSink` production impl: `MediaStoreDocumentsSink` (MediaStore Documents collection, relative path `Nitpicker/`, MIME `text/markdown`). **Called by:** SessionService (auto at stop), DetailViewModel (re-export). **Tested against** `InMemoryDocumentSink` (§12) — MediaStore impl itself verified on-device (AC-3).

### 8.12 AnalysisEngine

```kotlin
class AnalysisEngine(private val transport: ChatTransport) {
    suspend fun analyze(ctx: AnalysisJobContext): AnalysisResult
}
```
Builds request per §6.5 from `ctx` (targets with per-session `seq` numbers — the LLM sees small seqs; `AnalysisResult` maps back to statement ids), parses with the same one-repair policy as §8.8, validates per §6.5 (family enum, seq∈targets, quote substring). **Called by:** PipelineOrchestrator.analysisWorker.

### 8.13 ConnectivityMonitor / PipelineMetrics / SessionLog

```kotlin
interface ConnectivityMonitor { val state: StateFlow<NetworkState> }
class SystemConnectivityMonitor(context: Context) : ConnectivityMonitor  // ConnectivityManager callback
class PipelineMetrics { val snapshot: StateFlow<PipelineSnapshot>; fun update(f: (PipelineSnapshot) -> PipelineSnapshot) }
class SessionLog(private val filesDir: File) {
    fun log(sessionId: Long, level: String, tag: String, msg: String)
    fun deleteForSession(sessionId: Long)    // caller: DetailViewModel session-delete flow (§8.14)
}
```
Metrics is in-memory only (observability, not state — lost on death by design). Log: `filesDir/logs/session-{id}.log`, `[+mm:ss] LEVEL TAG msg`, head-truncated at 512 KB. Both are debug facility (removed with A-5). **Called by:** workers, SessionService; UI reads `snapshot`; `deleteForSession` called by DetailViewModel.

### 8.14 UI layer (Compose; no network, no DAO access — ViewModels only)

| Screen | ViewModel (constructor deps) | State / actions |
|---|---|---|
| MainActivity + AppNav | — | Compose host (`ui/MainActivity.kt`); nav graph Start ↔ Live ↔ List ↔ Detail ↔ Settings (`ui/navigation/AppNav.kt`) |
| StartScreen | `StartViewModel(container)` | language toggle (De/En); **Start** disabled until API key set (`SettingsStore.apiKeySet`) and RECORD_AUDIO granted; `start(language)` → Intent ACTION_START |
| LiveScreen | `LiveViewModel(container)` | `Flow<List<StatementView>>` (statements + annotations + resolved speaker names), `StateFlow<PipelineSnapshot>`; `FLAG_KEEP_SCREEN_ON`; auto-scroll to newest, pause on scroll-up, "jump to latest" chip; provisional statements render with trailing `…`; annotations as italic lines with family prefix; **Stop** → ACTION_STOP; during STOPPING shows "Finalizing…" + Force finalize; StatusPanel (collapsible) renders snapshot |
| ListScreen | `ListViewModel(container)` | sessionsFlow; Recover action for ACTIVE/STOPPING; open detail |
| DetailScreen | `DetailViewModel(container)` | read-only protocol (same rendering); rename tag, merge tag A→B (B must be unmapped); re-export; delete session (`repo.deleteSession` + `retention.deleteForSession` + `log.deleteForSession`; exported files untouched) |
| SettingsScreen | `SettingsViewModel(container)` | asr model, retention minutes, chunk max sec, vad silence, API key (write-only field) |

```kotlin
data class StatementView(val id: Long, val seq: Long, val speaker: String, val text: String,
    val state: StatementState, val startOffsetMs: Long, val annotations: List<AnnotationView>)
data class AnnotationView(val family: AnnotationFamily, val subType: String, val note: String)
```

---

## 9. Background Jobs

| Job | Mechanism | Trigger | Notes |
|---|---|---|---|
| asrWorker / segmentationWorker / analysisWorker | `SessionService`-scoped coroutines observing DAO flows | session ACTIVE/STOPPING | not WorkManager (Doc 1 D-8 — redundant under a live FGS) |
| Retention pruning | direct call | session start | `retention.prune()` |
| In-flight reset | direct call | service start / resume | §8.9 |
| Log rotation | on write | each log line | head-truncate at cap |

No other background work exists; app has no boot receiver, no sync, no alarms.

---

## 10. Observability

- **Status panel (LiveScreen, debug facility):** renders `PipelineSnapshot` — network state, session clock, queue depths, last ASR/LLM latencies, call/failure counters. One glance answers "is it working".
- **Session log:** per-session plain text (format §8.13) — chunk boundaries with offsets, dispatch/results with latencies, retry/failure events, retention moves, export outcome. Correlates with retained audio for Doc 1 §9.5-style debugging.
- **No remote telemetry** (Doc 1 §7) — the two instruments above are the complete observability surface.

---

## 11. Error Handling

**Typed exceptions:** `OpenRouterHttpException(status)`, `OpenRouterTimeoutException`, `OpenRouterAuthException`, `MalformedLlmOutputException`, `MicLostException`, `ExportIOException`.

| Failure | Policy |
|---|---|
| ASR/LLM 429, 5xx, IO | retry ≤3 with backoff (§4.2); 429 honors Retry-After |
| ASR retries exhausted | chunk FAILED, gap marker in export, pipeline continues |
| LLM parse failure (post repair) | job FAILED (analysis) / chunk statements skipped (segmentation, gap marker); counted in status panel |
| Auth (401/403) | no retry; capture + queueing continue; status panel flags; operator fixes key in Settings; queues drain on next success |
| Mic lost | session → FAILED; partial export offered; explicit UI state |
| Storage full at export | `ExportIOException` → UI error + Retry button; session data intact in DB |
| Process death | DB recovery path (§8.10); in-flight states reset; zero data loss by construction (§5.1) |
| Offline mid-session | workers park; queues grow; auto-drain in order on reconnect (R-F12) |

**Invariant:** no failure path can block the capture→queue path except mic loss itself; every other failure degrades annotations or transcripts, never the recording durability.

---

## 12. Testing Strategy

**Test-construction rule (binding):** *a test may not hand-build the state the code under test is supposed to produce. Pre-existing state is established by calling the production path, never by direct fixture writes. A test that pre-builds its inputs verifies arithmetic while proving nothing about whether the pipeline can produce them.*

**Harness:** `PipelineHarness` constructs the real `SessionRepository` (in-memory Room under Robolectric), real `Chunker`/`WavEncoder`/`AudioRetentionStore`/`MarkdownExporter`, real workers — with `ScriptedChatTransport` (typed `TranscriptionResponse` ASR transcripts, chat segmentation/analysis payloads, one sequential cross-endpoint response/error script), `FakeNetworkGate`, `InMemoryDocumentSink`. Chunks are produced by feeding synthesized PCM (silence + speech-energy bursts) through `Chunker.offer` — the production chunking path (its production caller, AudioCaptureEngine, is hardware-bound and is covered by on-device acceptance only). Domain state (statements, annotations, jobs) is **only ever** created by driving the harness pipeline — tests assert against DB reads, never seed domain rows.

**Unit tests:** VAD boundaries (silence close, max-length close, min-speech merge); `WavEncoder` byte-exactness (fixed input → expected 44+PCM bytes); `Prompts` content (equal-priority clause present, internal-knowledge-only clause present, language injected, both families in schema — R-F6/CA-5 as testable contract); `LlmJsonParser` (fenced JSON, trailing text, repair path); claims cap enforcement; annotation validation (bad family dropped, quote substring rule); `MarkdownExporter` rendering against sessions built through the harness.

**Gateway tests (MockWebServer, real OkHttp round trip):** 429 + Retry-After honored; 500→retry→success; 401 no-retry; timeout; parse of real response shape.

**Integration tests (harness):**
- TC-PIPE-1 happy path: PCM → chunk → transcript → 2 statements + annotations + claims in DB, chunk reaches DISCARDED, blob handled per retention config.
- TC-PIPE-2 continuation: chunk N+1 `continuesPrevious=true` → statement extended in place, REANALYSIS job created and replaces annotations.
- TC-PIPE-3 offline backfill: gate OFFLINE mid-stream, N chunks queued, gate ONLINE → in-order drain, no loss, late annotations attach.
- TC-PIPE-4 stop → drain → export: `ExportOutcome` uri + file content match; STOPPING→COMPLETED.
- TC-PIPE-5 recovery: build container #2 on same DB (simulated process death) → in-flight reset → drain completes.
- TC-PIPE-6 ASR terminal failure → FAILED chunk → gap marker in export; pipeline continues.
- TC-PIPE-7 retention: `retention_minutes=2` → only in-window blobs become files; prune keeps 10 sessions; `retention_minutes=0` → no files.
- TC-PIPE-8 provisional finalization: last PROVISIONAL statement finalized at session end.
- TC-PIPE-9 speaker mapping: rename + merge → export shows mapped names; raw tags untouched in DB.

**On-device acceptance (not automated):** §14.

---

## 13. Build / Run / Deploy

```bash
./gradlew :app:testDebugUnitTest          # full §12 suite
./gradlew :app:assembleDebug              # debug APK (direct install)
./gradlew :app:assembleRelease            # requires keystore.properties (storeFile, storePassword,
                                          # keyAlias, keyPassword) — git-ignored
adb install -r app/build/outputs/apk/release/app-release.apk
```
Release signing via `keystore.properties` read in `build.gradle.kts` (never committed). On-device updates: same-signature APK over-install preserves data. `minSdk 30`, `targetSdk` pinned to current stable at first commit. First run: grant mic + notification permissions, enter API key in Settings.

---

## 14. Acceptance Criteria

| ID | Criterion | Procedure |
|---|---|---|
| AC-1 | ≥80 % of statements visible ≤10 s after end-of-utterance; ≥80 % of annotations ≤14 s | live session; compare session-log statement offsets against retained-audio playback |
| AC-2 | 90-min session, one forced 60 s Wi-Fi drop: zero lost chunks, ordered backfill | status panel + session log + export completeness |
| AC-3 | Exported `.md` appears in Documents/Nitpicker, opens as valid Markdown | device file manager |
| AC-4 | Rename/merge in Detail → re-export reflects names; raw tags unchanged in DB | TC-PIPE-9 + manual |
| AC-5 | Retention: files exist only for first N minutes; prune keeps ≤10 sessions | TC-PIPE-7 + device check |
| AC-6 | Mic permission revoked mid-session → FAILED state + partial export offered | manual |
| AC-7 | Kill app mid-session → relaunch offers recovery → drain completes, gap marked | TC-PIPE-5 + manual |
| AC-8 | Two-speaker live session, one DE one EN: operator judges transcript usable; annotations reviewed against retained audio (false-positive rate noted, prompt tuning if needed) | Doc 1 §9.5 debug workflow; Doc 1 risks 1–2 |
| AC-9 | Observed OpenRouter spend for the AC-1/AC-2 sessions < $2 per 90-min session | OpenRouter dashboard |
| AC-10 | No sound/vibration from any app event; FGS notification channel silenced | manual |
| AC-11 | No plaintext API key in DataStore file or logs; key functional after app restart | device explorer + §7 |

---

## 15. Seam Register & Call Traces

**Composition root: `di/AppContainer.kt`.** Constructed once by `CompanionApp.onCreate()` in this order: `SettingsStore(context)` → `SystemConnectivityMonitor(context)` → `AppDatabase` / `SessionRepository(db)` → `AudioRetentionStore(filesDir)` → `ChatTransport = OpenRouterGateway(GatewayConfig(baseUrl, settings::apiKey))` → `AsrClient(transport, settings)` → `StatementProcessor(transport)` → `AnalysisEngine(transport)` → `PipelineMetrics()` → `SessionLog(filesDir)` → `MarkdownExporter(repo, MediaStoreDocumentsSink(context))`. `SessionService` and all ViewModels **retrieve** these from `AppContainer` — no other module constructs infrastructure. `PipelineOrchestrator` and `AudioCaptureEngine` are constructed by `SessionService` (they are session-scoped, not app-scoped) with AppContainer-provided dependencies — the service acts under the composition root's authority; this is the sole sanctioned exception and is recorded here deliberately.

### 15.1 Seam register (built from the traces below)

| # | Seam (from → to) | Invoking signature | Mechanism | Durable state at boundary |
|---|---|---|---|---|
| S1 | framework → AudioCaptureEngine | `start(scope)` | direct | — (mic open) |
| S2 | AudioCaptureEngine → Chunker | `offer(PcmFrame)` | direct, real-time | none (in-memory buffer by design) |
| S3 | Chunker → AudioCaptureEngine | `onChunkClosed(AudioChunkData)` | callback | none yet |
| S4 | AudioCaptureEngine → SessionRepository | `enqueueChunk(sessionId, chunk)` | direct, suspend | **audio_chunks row CAPTURED + WAV blob** |
| S5 | asrWorker → SessionRepository | `claimNextChunkForAsr(sessionId)` | direct | state ASR_PENDING (txn) |
| S6 | asrWorker → AsrClient | `transcribe(AudioChunkRow): TranscriptResult` | direct | none (network only) |
| S7 | AsrClient → ChatTransport | `postTranscription(TranscriptionRequest): TranscriptionResponse` | direct (HTTP) | none |
| S8 | asrWorker → SessionRepository | `saveTranscript(TranscriptResult)` | direct | transcript + ASR_DONE (txn) |
| S9 | segWorker → SessionRepository | `claimNextChunkForSegmentation(sessionId)` | direct | claim marker |
| S10 | segWorker → StatementProcessor | `segment(chunk, tail, tags, language): SegmentationResult` | direct | none |
| S11 | StatementProcessor → ChatTransport | `postChat` | direct (HTTP) | none |
| S12 | segWorker → SessionRepository | `applySegmentation(chunkId, sessionId, SegmentationResult)` | direct | **statements + analysis_jobs PENDING (txn)** |
| S13 | analysisWorker → SessionRepository | `claimNextAnalysisJob(sessionId): AnalysisJobContext?` | direct | job IN_FLIGHT (txn) |
| S14 | analysisWorker → AnalysisEngine | `analyze(ctx): AnalysisResult` | direct | none |
| S15 | AnalysisEngine → ChatTransport | `postChat` | direct (HTTP) | none |
| S16 | analysisWorker → SessionRepository | `saveAnalysis(jobId, sessionId, AnalysisResult)` | direct | **annotations + claims + job DONE + chunk ANALYZED (txn)** |
| S17 | analysisWorker → AudioRetentionStore / SessionRepository | `retainIfNeeded(...)`; `markDiscarded`; `nullBlob` | direct | retained file; blob nulled |
| S18 | SessionRepository → UI | `statementsFlow` / `annotationsFlow` / `queueDepthsFlow` | Room Flow | read-only projection |
| S19 | LiveViewModel → SessionService | Intent ACTION_STOP | Android IPC | session STOPPING (txn) |
| S20 | SessionService → MarkdownExporter | `export(sessionId): ExportOutcome` | direct | export file + COMPLETED + export_uri (txn) |
| S21 | DetailViewModel → SessionRepository / MarkdownExporter / SessionLog / AudioRetentionStore | `upsertMapping`; `export(sessionId)`; `deleteForSession` | direct | mapping row / re-export / deleted files |
| S22 | workers → ConnectivityMonitor | `state` | StateFlow read | none |
| S23 | workers → PipelineMetrics | `update {...}` | direct | none (by design) |
| S24 | StartViewModel → SessionService | Intent ACTION_START(language) | Android IPC | session row ACTIVE (txn) |
| S25 | ListViewModel → SessionRepository | `sessionsFlow()`, `activeOrStopping()` | Flow / direct | read-only |
| S26 | SettingsViewModel → SettingsStore | typed setters | direct | DataStore |
| S27 | SessionService → AudioRetentionStore | `prune()` | direct | retained/ dirs |
| S28 | CompanionApp → AppContainer | constructor | direct | all singletons |
| S29 | SessionService / ViewModels → AppContainer | property retrieval | direct | — |
| S30 | workers → SettingsStore | `snapshot(): SettingsSnapshot` | direct | none (read-only) |

### 15.2 Call traces (primary runtime paths; every callee has module, caller, param/return types, and boundary-surviving state)

**T1 — Session start.** StartScreen button → `StartViewModel.start(DE)` → `context.startForegroundService(Intent(ACTION_START, language=DE))` → `SessionService.onStartCommand` → `startForeground(type=MICROPHONE)` → retrieve AppContainer deps → `repo.createSession(SessionParams(...))` [**sessions row ACTIVE**] → `retention.prune()` → construct `AudioCaptureEngine(sessionId, chunkMaxSec) { chunk → WavEncoder.toWav → repo.enqueueChunk }` → `engine.start(scope)` → `orchestrator.recoverInFlight(sessionId)` → `orchestrator.startWorkers(sessionId, scope)`. UI navigates to LiveScreen; `statementsFlow` emits empty list.

**T2 — Utterance → statement → annotation.** AudioRecord frame → `engine` read loop → `chunker.offer(PcmFrame)` → silence/max close → `onChunkClosed(AudioChunkData)` → `WavEncoder.toWav` → `repo.enqueueChunk` [**CAPTURED + blob**] → asrWorker Flow emission → (gate ONLINE) `repo.claimNextChunkForAsr` [**ASR_PENDING**] → `asrClient.transcribe` → `transport.postTranscription` (HTTP, R-N1) → `TranscriptResult` → `repo.saveTranscript` [**transcript + ASR_DONE**] → segWorker → `repo.claimNextChunkForSegmentation` → tail context read → `statementProcessor.segment` → `transport.postChat` → `SegmentationResult` → `repo.applySegmentation` [**statements PROVISIONAL/FINAL + CHUNK analysis_job PENDING**] → LiveScreen recomposes via `statementsFlow` (statement visible; AC-1 clock stops here) → analysisWorker → `repo.claimNextAnalysisJob` [**IN_FLIGHT**] → `analysisEngine.analyze(ctx)` → `transport.postChat` → `AnalysisResult` → `repo.saveAnalysis` [**annotations + claims + DONE + chunk ANALYZED**] → `retention.retainIfNeeded(..., settings.snapshot())` → `repo.markDiscarded`/`nullBlob` [**DISCARDED; retained file if in window**] → annotation visible via `annotationsFlow` (AC-1 second clock stops).

**T3 — Continuation.** Chunk N+1 segmentation returns `continuesPrevious=true` → inside `applySegmentation`: `statementDao.extendStatement` (append text, extend end offset) + **always** insert REANALYSIS job [**statement extended + job PENDING**] → analysisWorker → `analyze` on fresh text → `saveAnalysis` deletes old annotations for target, inserts new [**replaced annotations**]. UI shows extended text in place (same statement id — Flow re-emits).

**T4 — Network drop.** `gate.state → OFFLINE` → workers park before transport calls; capture path unaffected → `enqueueChunk` continues [**CAPTURED rows accumulate**] → `gate.state → ONLINE` → workers resume; claims processed in seq order; statements/annotations attach late (accepted, R-F12); status panel shows depth spike then drain.

**T5 — Stop & export.** Stop button → `LiveViewModel.stop()` → Intent ACTION_STOP → `repo.setStatus(STOPPING)` [**STOPPING**] → `engine.stop()` (drains final chunk through S2–S4) → workers continue until `drainComplete` (queues empty ∧ segmentation pending empty) → `repo.finalizeSession` [**all PROVISIONAL → FINAL**] → `markdownExporter.export(sessionId)` → `documentSink.write(...)` [**.md in Documents/Nitpicker**] → `repo.setExportUri` + `setStatus(COMPLETED)` [**COMPLETED**] → LiveScreen shows outcome, service stops. Stall >120 s → Force finalize button → ACTION_FORCE_FINALIZE → export partial + remaining jobs/chunks FAILED.

**T6 — Process death & recovery.** OS kills process mid-session → app relaunch → `CompanionApp` → `AppContainer` → `ListViewModel.activeOrStopping()` returns the session → user taps Recover → Intent ACTION_RESUME(sessionId) → service reconstructs engine + orchestrator → `recoverInFlight` [**ASR_PENDING→CAPTURED; IN_FLIGHT→PENDING**] → workers drain DB queues; recording gap start = death time; export marks `[recording gap …]`.

**T7 — Speaker rename & re-export.** DetailScreen → `DetailViewModel.rename("S2","Müller")` → `repo.upsertMapping` [**mapping row**] → `statementsFlow` re-render (display name) → `reExport()` → `markdownExporter.export(sessionId)` → new `.md` with mapped names (S21).

**Trace audit:** every callee in T1–T7 is a named module with a named caller, defined parameter and return types (§8), and committed state at each process boundary (bold). No orphan callees; no gaps — gaps would be [CONFLICT]s and there are none.

---

## 16. Final Consistency Checklist

**Constraint honoring (Doc 1 §2 → Doc 2):** R-F1→§6.3, §8.6; R-F2→§8.9 segWorker, §5.1 statements; R-F3→§8.14 Detail, §8.11 mapping overlay, TC-PIPE-9; R-F4/R-F5/R-F6→§8.7 Prompts (equal-priority clause), §6.5 single schema, §8.12; R-F7→§5.1 claims, §8.7 digest rule; R-F8→§8.14 rendering (italic, silent), AC-10; R-F9→§8.7 language injection, §8.10 start; R-F10→§8.11, AC-3; R-F11→§8.4, TC-PIPE-7, AC-5; R-F12→§8.9 gate, T4, AC-2; R-F13→§8.14, §8.10; R-N1→§6.1 single transport to OpenRouter only; R-N2→§8.2 VAD is DSP (no ML anywhere in dependencies); R-N3→§2, §13; R-N4→§4.2/§13 minSdk 30; R-N5→AC-9; R-N6→§8.10 Wi-Fi lock, AC-2 environment; R-N7→§7; R-N8→§4.1 chunk_max_sec lever, AC-1. No section assumes a capability a constraint excludes: no streaming socket, no diarization model, no web/tool access in any prompt, no on-device inference, no second AI provider.

**Executability:** traces T1–T7 run end to end with committed state at every boundary (§15.2); the seam register (§15.1) is derived from them.

**Caller existence:** every module in §8 lists its caller(s); every DAO has exactly one caller (SessionRepository); every repository method has ≥1 worker/UI/exporter caller (S4–S27); every SettingsStore member has a named caller (`apiKeySet`→Start gating, `apiKey`→gateway provider, `snapshot()`→workers S30, setters→SettingsViewModel S26); `SessionLog.deleteForSession`→DetailViewModel (S21). No interface exists without an invoker — `ChatTransport` (AsrClient, StatementProcessor, AnalysisEngine, + test fakes), `ConnectivityMonitor` (workers), `DocumentSink` (MarkdownExporter). Interfaces exist for transport/OS-boundary injection, each justified by the §12 rule — [PROPOSED DESIGN DECISION D-12, refines Doc 1 §6.2 seam 3/4].

**Type closure:** every type in every signature is defined in §8.0, §5, or §6.2 — including `SettingsSnapshot` (§8.0, producer §4.1, consumers §8.4/§8.9). Verified by scan; no context-object referenced only inside a signature.

**Doc 1 refinements (explicit, none silent):** `audio_blob` durability semantics replace Doc 1 §5's `uploadBytes?` field (same intent, precise mechanism — required by R-F12); `ANALYZED→DISCARDED` terminal transition given concrete trigger (§8.9); D-12 interface injections. All refine without contradicting; no capability added.

**Test-construction rule:** stated verbatim in §12 and structurally enforced by the harness (domain rows only via production pipeline) and by repository-as-sole-DAO-caller.

**No placeholders:** version floors are compatibility constraints, not TBDs; exact pins are a first-commit action (Doc 3 WP-01). No invented third-party services beyond the operator-specified OpenRouter.

---
