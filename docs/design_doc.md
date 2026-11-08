# Document 1 — Design Document

**Title:** Nitpicker — Live Transcription, Protocol & Analysis App for Android
**Version:** 1.1.0
**Date:** 2026-11-08
**Status:** Approved — endpoint correction explicitly authorized by the operator on 2026-11-08
**Prepared per:** Multiphase Interview Tech Docs workflow v1.2.0; based on the accepted Interview Readiness Summary of 2026-10-04.

**Revision History**

| Version | Date | Changes |
|---|---|---|
| 1.0.0 | 2026-10-04 | Initial draft. |

| 1.1.0 | 2026-11-08 | Correct the ASR request contract to multipart OpenRouter audio transcriptions, preserving binary WAV and session language; segmentation/analysis remain chat completions. Replaces the outdated A-6 chat-audio assumption following live verification. |

---

## 1. Overview & Goals

### 1.1 System context

A single-user, single-device Android application. No backend, no server component, no accounts. All AI inference runs through OpenRouter (CA-1). The phone is placed on a table during consented business meetings (2–6 participants, unknown in advance — OR-11) and functions as the executive's Nitpicker: it transcribes what is said, builds a live on-screen protocol of discrete statements, and continuously annotates those statements with fact-checking and logical/rhetorical analysis, both of exactly equal standing (OR-4, OR-5).

### 1.2 Goals

1. **G1 — Live protocol.** Everything said appears as discrete, timestamped statements on a scrolling, screen-on display, as near to the utterance as the platform permits (OR-2, OR-6, CA-6, OR-10).
2. **G2 — Continuous dual analysis.** Every statement is silently annotated by `google/gemini-3.1-flash-lite` (CA-3) with (a) fact-checking against model-internal knowledge and (b) logical/rhetorical analysis — logical errors, imprecisions, false conclusions, false assumptions, rhetorical figures/tricks. Neither function outranks the other in cadence, prompting priority, or rendering (OR-4, OR-5, CA-5).
3. **G3 — Best-effort speaker attribution.** Statements carry inferred speaker tags (Speaker 1…N), visually marked as inferred, renamable and mergeable after the session (OR-3, CA-12).
4. **G4 — Durable, exportable record.** Protocol persists on-device; each session auto-exports as plain Markdown to a user-accessible folder (OR-2, CA-8).
5. **G5 — Debuggability during the validation phase.** The first minutes of each session's audio — the exact bytes sent to OpenRouter — are retained on-device for testing/debugging/verification; this facility is temporary with a defined end-state (OR-9, PDD-3, PDD-4, A-5).

### 1.3 Success criteria (design level)

- Statement visible on screen within ~4–10 s of utterance end; annotation within ~6–14 s (latency budget, §3.4; acceptance is assumption A-2, to be validated per Doc 2 §14).
- A 90-minute session completes without data loss over company Wi-Fi including at least one mid-session network drop (CA-4).
- Misattribution, when it occurs, is always visibly marked as inference, never presented as fact (OR-3, CA-12).
- Annotations read as observations, not verdicts (risk mitigation for the flagship failure mode of both analysis functions).

---

## 2. Requirements Summary

This section is the governing constraint register. Every later section must honor it; the constraint-to-section map at the end of §2 is the audit instrument. Traceability tags: **OR** = operator requirement (from the original brief), **CA** = confirmed interview answer, **A** = assumption, **PDD** = proposed design decision accepted by the operator. *Traceability rule: operator emphasis figures are not spec quantities and are not recorded as such.*

### 2.1 Functional requirements

| ID | Requirement | Source |
|---|---|---|
| R-F1 | Live transcription of table audio via OpenRouter ASR (`openai/whisper-large-v3-turbo` or `microsoft/mai-transcribe-2`), stateless chunked requests | OR-1, CA-1, CA-2 |
| R-F2 | Continuous display and persistence of a protocol of discrete, timestamped statements | OR-2 |
| R-F3 | Best-effort inferred speaker tags, marked as inferred, rename/merge after session | OR-3, CA-12 |
| R-F4 | Fact-checking of factual claims (numbers, dates, quantities, names, assertions of fact) against model-internal knowledge only | OR-4, CA-5 |
| R-F5 | Logical/rhetorical analysis: logical errors, imprecisions, false conclusions, false assumptions, rhetorical figures/tricks | OR-5 |
| R-F6 | R-F4 and R-F5 have exactly equal standing: same processing cadence, same call, same annotation class and style | OR-4, OR-5 |
| R-F7 | Session-internal consistency checking (claims checked against statements earlier in the same meeting) | PDD-2 |
| R-F8 | Silent annotations rendered in italics attached to their statement; no sound, vibration, or notification for analysis output | OR-10 |
| R-F9 | German or English, chosen by toggle at session start, locked for the session, never mixed | OR-12, CA-10 |
| R-F10 | Automatic per-session plain-Markdown export to a user-accessible folder | CA-8 |
| R-F11 | Retain first N minutes of session audio (default 10, runtime-configurable 0–10; 0 = discard-immediately) as exact upload bytes, app-private, auto-pruned | OR-9, PDD-3, PDD-4 |
| R-F12 | Buffer-and-backfill on network drops; sessions never start offline | CA-4 |
| R-F13 | Manual session start/stop; screen stays awake during session; browsable session list | CA-11 |

### 2.2 Non-functional requirements

| ID | Requirement | Source |
|---|---|---|
| R-N1 | All AI inference exclusively via OpenRouter; no other AI service, cloud or on-device | CA-1 |
| R-N2 | No on-device ML; on-device signal processing limited to non-ML DSP (energy VAD) | CA-1, OR-8 context |
| R-N3 | Single sideloaded APK; no Play Store; no extra installs; native Kotlin | OR-13, OR-8 |
| R-N4 | Hardware floor: Samsung Galaxy A50-class; min Android 11 (API 30) | OR-7, A-3 |
| R-N5 | Sessions 10–90 min; 4–5/month, occasionally more; ≤ $25/month inference spend | CA-7 |
| R-N6 | Company Wi-Fi as the connectivity environment | CA-4 |
| R-N7 | No encryption beyond Android defaults; API-key credential hygiene excepted (§7) | CA-8 |
| R-N8 | Latency: annotations within seconds of the utterance, near-utterance freshness; chunk length is the tuning lever | OR-6, CA-6, A-2 |

### 2.3 Non-goals

Acoustic diarization (cloud or on-device) · web-grounded fact-checking · cross-session speaker profiles · mixed-language conversations · active alerts of any kind · cloud sync · multi-user/multi-device · Play Store distribution · offline session start · on-device ASR fallback · translation, TTS · encryption beyond Android defaults · permanent audio archiving (retention is a bounded, temporary debug facility — A-5).

### 2.4 Constraint-to-section honoring map

| Constraint | Honored in |
|---|---|
| R-F1, R-N1 (OpenRouter-only, chunked ASR) | §3.2, §4 (AsrClient), §6.1 |
| R-F2 (statement protocol) | §4 (StatementProcessor), §5, §6.2 |
| R-F3 (inferred tags, rename/merge) | §4 (StatementProcessor, UI), §5 (SpeakerMapping), §6.2 |
| R-F4/R-F5/R-F6 (co-equal analysis) | §4 (AnalysisEngine), §6.2, §10 D-2 |
| R-F7 (session-internal consistency) | §4 (AnalysisEngine, claims digest), §5, §10 D-3 |
| R-F8 (silent italic annotations) | §4 (UI), §6.3 |
| R-F9 (language lock) | §3.2, §6.1–6.2, §10 D-10 |
| R-F10 (Markdown export) | §4 (MarkdownExporter), §6.3 |
| R-F11 (audio retention) | §4 (AudioRetentionStore), §5, §7, §10 D-9 |
| R-F12 (buffer-and-backfill) | §3.3, §4 (PipelineOrchestrator), §9 |
| R-F13 (session lifecycle UX) | §4 (UI), §9 |
| R-N2 (no on-device ML) | §3.2, §10 D-4 |
| R-N3 (single Kotlin APK) | §8 |
| R-N4 (A50 floor, API 30) | §8, §9 (battery guidance) |
| R-N5 (budget) | §8.4 |
| R-N7 (no extra encryption) | §7 |
| R-N8 (latency) | §3.4, §10 D-1 |

---

## 3. System Architecture

### 3.1 Architecture overview

Single-process Android app. A foreground service owns the audio pipeline; the UI is a pure projection of persisted state and never performs network calls. All state lives in one on-device database (single source of truth); every pipeline stage reads and writes through it, which makes the pipeline resumable and the UI trivially consistent.

```
┌──────────────────────────── Android device (single process) ────────────────────────────┐
│                                                                                          │
│  UI layer (Compose)                                                                      │
│   LiveProtocolScreen ─ SessionList ─ SessionDetail ─ Settings                            │
│        ▲ Room Flows (observe only; no network in UI)                                     │
│        │                                                                                 │
│  SessionService (foreground, type=microphone)                                            │
│   ├── AudioCaptureEngine (AudioRecord, 16 kHz mono PCM)                                  │
│   ├── Chunker (energy VAD — DSP, non-ML) ──► AudioRetentionStore (first N min,          │
│   │                                              exact upload bytes, app-private)       │
│   ├── PipelineOrchestrator (FIFO queues, network watch, retry/drain)                     │
│   │      │                                                                               │
│   │      ▼                                                                               │
│   ├── AsrClient ────────────► OpenRouter (ASR model, audio in → text out)                │
│   ├── StatementProcessor ───► OpenRouter (gemini-3.1-flash-lite: segmentation)           │
│   └── AnalysisEngine ───────► OpenRouter (gemini-3.1-flash-lite: fact-check +            │
│                               logic/rhetoric + claims extraction, one call)              │
│                                                                                          │
│  SessionRepository (Room DB — single source of truth) ──► MarkdownExporter ──► .md files │
│                                                          (user-accessible folder)         │
└──────────────────────────────────────────────────────────────────────────────────────────┘
```

### 3.2 Runtime pipeline

1. **Capture.** `AudioCaptureEngine` records 16 kHz, mono, 16-bit PCM via `AudioRecord` (R-F1; format rationale §10 D-7).
2. **Chunking.** `Chunker` applies energy-based VAD (pure DSP — R-N2). A chunk closes on ≥ 0.9 s of silence or at 8 s maximum speech; speech bursts shorter than 1.2 s merge into the following chunk. Each chunk is emitted with a monotonic sequence number and precise timestamps (R-F1, R-N8 lever).
3. **Retention copy.** While the session clock is within the retention window (default 10 min, PDD-3), the chunk's exact upload bytes are copied to `AudioRetentionStore` before dispatch (R-F11, PDD-4). Beyond the window, no copy is made — discard-after-transcription applies from that mark onward (OR-9).
4. **ASR.** `AsrClient` sends the chunk to the configured OpenRouter audio-transcriptions endpoint with the session language as an ISO language field and an optional verbatim instruction (R-F1, R-F9). Transcript text is persisted with the chunk record.
5. **Segmentation.** `StatementProcessor` sends the raw transcript plus tail context (last statement text, recent speaker tags) to flash-lite, receiving structured statements: text, continuation flag, inferred speaker tag (R-F2, R-F3, PDD-1). Statements receive global IDs and render immediately — provisional, consolidated in place as later chunks arrive.
6. **Analysis.** `AnalysisEngine` sends the new statements, a rolling window of recent statements, and the session claims digest to flash-lite in a single call (R-F6). It returns annotations of two co-equal families — fact-check (R-F4) and logic/rhetoric (R-F5) — plus new claim entries for the digest (R-F7). Annotations attach to statement IDs and render in italics (R-F8).
7. **Persistence & export.** Every artifact is written to the Room DB as it is produced. On session stop, `MarkdownExporter` writes the protocol as `.md` to the user-accessible folder, applying the speaker rename/merge overlay (R-F10, R-F3).

### 3.3 Degradation model (buffer-and-backfill)

The orchestrator holds two durable FIFO queues in the DB: **ASR-pending** (captured chunks) and **analysis-pending** (finalized statements). On connectivity loss, capture continues; queues grow. On reconnect, queues drain strictly in order; downstream stages follow naturally. Analysis results for older statements arrive late and attach in place — acceptable per CA-4. Queue depth beyond a threshold (~20 chunks ≈ 3 min of speech) surfaces in the status panel (§9.3). Sessions are never started offline (CA-4), but the architecture degrades to queueing regardless, so an unexpected offline start cannot lose data.

### 3.4 Latency budget (R-N8)

| Stage | Typical | Notes |
|---|---|---|
| Chunk accumulation | 0–8 s from utterance start | silence closes earlier; 8 s hard max |
| Encode + enqueue | < 0.1 s | WAV, ~32 KB/s |
| Upload (company Wi-Fi) | 0.3–2 s | ~260 KB per 8 s chunk |
| ASR inference | 1–4 s | model-dependent |
| Segmentation call | 1–3 s | flash-lite, small context |
| **Statement visible** | **~4–10 s after utterance end** | |
| Analysis call | 2–4 s | one call, both families (D-2) |
| **Annotation visible** | **~6–14 s after utterance end** | |

The single tuning lever is maximum chunk length (5–15 s configurable): shorter chunks tighten freshness at the cost of more calls; A-2 records that the mid-range (8 s) is expected to satisfy the near-utterance bar. Validation is a Doc 2 §14 acceptance test.

### 3.5 Declared conflicts (resolved)

- **[CONFLICT-1] "Silent operation" (R-F8) vs. Android's mandatory foreground-service notification for microphone-type services.** Resolution: the FGS notification is OS-mandated and retained — it doubles as the persistent recording/consent indicator (a feature, not a bug, in a consented-recording context). Its channel is silenced (no sound). Analysis output never generates notifications. Considered resolved; no residual.
- **[CONFLICT-2] "Near-utterance freshness" (R-N8) vs. OpenRouter's stateless batch nature (no streaming ASR socket exists there — CA-1).** Resolution: latency budget above with tuning lever; acceptance recorded as A-2 and gated by a Doc 2 §14 test. Residual risk documented (§11).
- **[CONFLICT-3] "Identify speakers" (OR-3) vs. OpenRouter-only (R-N1) and no on-device ML (R-N2).** No OpenRouter ASR model performs acoustic diarization (A-1). Resolved by operator demotion: speaker identification is the least important requirement; attribution is text-inferred, best-effort, visibly marked (R-F3). Resolved; residual = misattribution risk, accepted.

---

## 4. Component Responsibilities

| Component | Responsibility | Traces |
|---|---|---|
| **SessionService** | Foreground service (type `microphone`). Owns session lifecycle, partial wake lock, Wi-Fi lock. Hosts the orchestrator and pipeline workers. Survives UI process death; session state is resumable from the DB. | OR-7, R-F13, D-8 |
| **AudioCaptureEngine** | `AudioRecord` reader; delivers PCM frames to Chunker; handles mic loss (route change, permission revocation) as session-fatal events with clear UI state. | R-F1 |
| **Chunker** | Energy VAD (DSP, non-ML). Chunk boundary policy (§3.2). Emits timestamped, sequenced chunks; hands exact byte copies to AudioRetentionStore within the retention window. | R-F1, R-N2, R-F11 |
| **AudioRetentionStore** | Stores exact upload bytes (WAV) for the first N minutes, app-private; enforces 0–10 min runtime config; prunes to the 10 most recent sessions. Temporary facility with designed removal path (A-5): setting 0 disables; deletion of the facility in a future version removes code + stored files. | R-F11, PDD-3, PDD-4 |
| **AsrClient** | OpenRouter multipart audio-transcriptions request (§6.1) for the configured ASR model; explicit session-language field and optional verbatim instruction; bounded retry with backoff; returns transcript text; records per-call latency for the status panel (usage only where supplied by the endpoint). | R-F1, R-F9, A-6 |
| **StatementProcessor** | Flash-lite segmentation call (§6.2): raw transcript + tail context → structured statements. Assigns global statement IDs; merges continuations into prior statements (seam-smoothing included in prompt contract, PDD-1); assigns provisional→final lifecycle; infers speaker tags with recent-tag context. | R-F2, R-F3, PDD-1 |
| **AnalysisEngine** | Flash-lite analysis call (§6.2): new statements + rolling window + claims digest → annotations (fact-check family and logic/rhetoric family, one call, equal standing) + claim entries appended to the digest. Prompt contract enforces observation-not-verdict phrasing and session language. | R-F4, R-F5, R-F6, R-F7 |
| **PipelineOrchestrator** | Durable FIFO queues (ASR-pending, analysis-pending); connectivity watch; ordered drain; retry and skip policies; ordering guarantees (ASR strictly FIFO; analysis in statement order); exposes queue depth and pipeline health to the status panel. | R-F12, R-N8 |
| **SessionRepository** | Room DB — single source of truth: sessions, statements, annotations, claims, chunk/queue state, speaker mappings, settings. All components read/write through it; UI observes Flows. | R-F2, D-8 |
| **MarkdownExporter** | Renders a session to plain Markdown (§6.3) at session stop (automatic) and on demand (re-export from SessionDetail); applies speaker rename/merge overlay; writes to a user-accessible folder. | R-F10, R-F3 |
| **UI layer (Compose)** | Start flow with De/En toggle; LiveProtocolScreen (keep-screen-on, auto-scroll with pause-on-scroll-up, in-place statement updates, italic annotations, provisional ellipsis); SessionList; SessionDetail (read-only protocol, speaker rename/merge, re-export, delete); Settings (API key, ASR model, retention minutes, chunk parameters); status panel (§9.3). No network access. | R-F8, R-F9, R-F13, OR-10 |

---

## 5. Data Model

Conceptual entities; exact DDL, indices, and migrations are Doc 2 §5 material. All timestamps are UTC epoch millis plus session-relative offsets for display.

- **Session** — `id`, `startedAt`, `endedAt`, `language (DE|EN)`, `status (ACTIVE|STOPPING|COMPLETED|FAILED)`, `asrModel`, `retentionMinutes`, `exportPath?`, `chunkMaxSec`. One row per meeting.
- **Statement** — `id (global, monotonic)`, `sessionId`, `seq`, `speakerTag? (inferred, e.g. "S2")`, `text`, `startedAtOffsetMs`, `endedAtOffsetMs?`, `state (PROVISIONAL|FINAL)`, `sourceChunkIds[]`. Continuation merges append text and extend timestamps rather than creating rows.
- **Annotation** — `id`, `statementId`, `family (FACT_CHECK|LOGIC_RHETORIC)`, `subType` (e.g., numeric-claim, false-conclusion, rhetorical-figure — free-form label from the prompt contract), `note`, `quote?` (triggering passage), `createdAtOffsetMs`. Both families share this one entity — equal standing by schema, not just by convention (R-F6).
- **ClaimEntry** — `id`, `sessionId`, `statementId`, `compactText` (one line, e.g., "S1: Q3 revenue €4.2m"), `createdAtOffsetMs`. Capped per session (drop-oldest, ~80 entries) to bound analysis context (D-3).
- **SpeakerMapping** — `sessionId`, `rawTag`, `displayName?`, `mergedIntoTag?`. Render-time overlay; raw inferred tags are never overwritten (R-F3).
- **AudioChunk** — `id`, `sessionId`, `seq`, `startedAtOffsetMs`, `durationMs`, `uploadBytes?` (retention window only), `state (CAPTURED|ASR_PENDING|ASR_DONE|ANALYZED|DISCARDED|FAILED)`, `transcript?`, `attemptCount`, `lastError?`. The queue tables and lifecycle states are the buffer-and-backfill mechanism (R-F12).
- **AppSettings** — `apiKeyRef` (keystore-encrypted, §7), `asrModel`, `retentionMinutes (0–10, default 10)`, `chunkMaxSec (5–15, default 8)`, `vadSilenceMs`, `claimsDigestCap`.

---

## 6. API & Interface Design

### 6.1 External: OpenRouter (audio transcriptions and chat completions — R-N1)

Three request shapes. All use the operator's existing OpenRouter account and key. ASR uses the dedicated audio-transcriptions endpoint; segmentation and analysis use chat completions. Doc 2 v1.2.0 defines the exact transport contracts.

**(a) ASR request** (AsrClient, per chunk): `POST /api/v1/audio/transcriptions`, multipart form-data:

| Field | Value |
|---|---|
| `model` | `openai/whisper-large-v3-turbo` or `microsoft/mai-transcribe-2` |
| `file` | `chunk.wav`, `audio/wav`, exact binary WAV upload bytes |
| `language` | `de` or `en`, locked to the session |
| `prompt` | Optional verbatim/no-translation instruction from `Prompts.asrInstruction` |

Response: JSON `{ "text": "transcript" }`. No chat messages or base64 audio are used for these transcription models. Native speaker metadata, if introduced and validated later, remains localized per A-1.

**(b) Segmentation request** (StatementProcessor, per transcript). System prompt establishes the contract; user content carries: raw transcript, last statement text (seam context), last ~6 speaker tags, session language. Response: strict JSON — `statements: [{ text, continuesPrevious: bool, speakerTag: string|null, endsProvisionally: bool }]`. The prompt contract requires: language matching (R-F9), seam smoothing when a chunk boundary cut mid-statement (PDD-1), tags only when inferable from conversational cues, `null` otherwise (R-F3).

**(c) Analysis request** (AnalysisEngine, per statement batch). System prompt establishes the contract; user content carries: new statements, rolling window (~20 recent statements), claims digest, session language. Response: strict JSON —
`{ annotations: [{ statementSeq, family: "FACT_CHECK"|"LOGIC_RHETORIC", subType, note, quote? }], claims: [ "…" ] }`.
Prompt contract requires (Doc 2 carries the full text): both families with equal priority (R-F6); model-internal knowledge only, never web (CA-5); session-internal consistency via the digest (R-F7); observation-not-verdict phrasing in the session language (§1.3); claims extraction limited to checkable factual assertions.

### 6.2 Internal seams (design level)

Named handoffs; exact signatures, types, and the full seam register with call traces are Doc 2 §15:

1. `AudioCaptureEngine → Chunker`: PCM frame stream (in-memory, real-time).
2. `Chunker → AudioRetentionStore`: exact upload-bytes copy (in-window only).
3. `Chunker → PipelineOrchestrator`: chunk envelope (bytes + metadata) → ASR-pending queue (DB-durable).
4. `PipelineOrchestrator → AsrClient`: chunk → OpenRouter → transcript (persisted on AudioChunk).
5. `AsrClient → StatementProcessor`: transcript + seam context → statements (persisted, IDs assigned).
6. `StatementProcessor → AnalysisEngine`: new statement batch → analysis-pending queue → annotations + claims (persisted).
7. `SessionRepository → UI`: Room Flows (statements, annotations, status) — the UI's only input.
8. `SessionService → MarkdownExporter`: session stop event → export.

### 6.3 Export format (MarkdownExporter)

One `.md` file per session: header block (date, start/end, duration, language, ASR model, statement count), then statements in order — `**[hh:mm:ss] S2:** text` (tag suffixed `?` when inferred and unmapped; display name substituted when mapped) — each followed by its annotations as italic lines prefixed by family: `*Fact-check: …*`, `*Logic: …*`, `*Rhetoric: …*`. Exact template is Doc 2 §8 material.

---

## 7. Security Architecture

- **Credential.** The OpenRouter API key is the only secret. Stored in app-private storage encrypted with an Android Keystore key — this is baseline credential hygiene, not the "additional encryption" declined in R-N7 (which governs meeting data). The key never appears in exports, logs, or the status panel.
- **Data egress inventory (complete).** Audio chunks → OpenRouter (ASR calls); transcript/statement/claims text → OpenRouter (flash-lite calls). Nothing else leaves the device. No telemetry, analytics, or crash reporting in the app.
- **Data at rest.** DB and retained audio: app-private storage (Android FBE applies by default — R-N7). Exports: user-accessible folder by explicit requirement (R-F10) — the protocol `.md` is deliberately user-reachable; this is the operator's accepted posture.
- **Recording transparency.** The OS-mandated FGS notification ("Nitpicker — recording") is the persistent on-tape indicator; [CONFLICT-1] resolution. Consent itself is social/legal and out of technical scope (operator's framing).
- **Residual.** Audio and text transit OpenRouter and its upstream model providers; their retention policies are outside this system's control. Accepted implicitly by the OpenRouter-only constraint (CA-1); recorded here so it is a documented acceptance, not an omission.

---

## 8. Infrastructure & Deployment

### 8.1 Topology

Phone-only. No servers, no databases beyond on-device Room, no CDN, no accounts. External dependency: OpenRouter (operator's existing account with prepaid credits).

### 8.2 Build & toolchain

Native Kotlin (R-N3), Jetpack Compose UI (modern default for a scrolling-list app; Views would also satisfy — decision D-11, no constraint forces either). Gradle build on a dev machine; standard Android toolchain. `minSdk 30` (Android 11 — microphone FGS type and A50 support, A-3); `targetSdk` = current stable at build time.

### 8.3 Distribution

Sideloaded APK (R-N3): release build signed with the operator's key, installed via "install unknown apps" grant per app. Updates = new APK over the old one (data preserved). No Play Store, no update infrastructure. Versioning: semver on the APK matching document versions.

### 8.4 Cost envelope (R-N5)

Order-of-magnitude: 4–5 sessions/month × ≤ 90 min ≈ 5–8 audio-hours ASR + a few hundred small flash-lite text calls. At current OpenRouter audio rates for these model classes this is single-digit dollars monthly — well under the $25 ceiling. Exact figures verified against OpenRouter's price page at implementation time (Doc 2 §4); the budget is not the binding constraint (latency is — §3.4).

### 8.5 Storage envelope

Retained audio worst case: 10 sessions × 10 min × ~1.9 MB/min ≈ 190 MB, bounded by pruning (PDD-4). DB and exports: single-digit MB. Trivial for A50-class storage.

---

## 9. Operational Model

### 9.1 Operator runbook

- **Pre-meeting:** phone charged (or charger at hand — see battery note); phone placed flat near table center, microphone unobstructed, away from HVAC/projector noise; start session; pick language (De/En).
- **During:** glance protocol; scrolling pauses when the operator scrolls up, resumes after idle. No interaction required. Status panel shows connection/queue state at a glance.
- **Post:** stop session → automatic `.md` export; optionally open SessionDetail to rename/merge speakers and re-export; optionally delete retained audio.
- **Mic placement guidance (risk-1 mitigation):** center of the table beats the executive's place setting; the phone's bottom-edge mic must face up and unobstructed; a 30-second live check (protocol shows statements) before the meeting proper is the sanity gate.

### 9.2 Failure modes and responses

| Failure | Response |
|---|---|
| Network drop mid-session | Capture continues; queues fill; auto-drain on reconnect (§3.3). No operator action. |
| ASR call fails after retries | Chunk marked FAILED, logged, skipped (transcript gap recorded in export as `[inaudible gap mm:ss]`); pipeline continues. |
| Malformed LLM JSON | One repair re-prompt; on second failure, stage output skipped (statements render unanalyzed; gap counted in status panel). |
| Mic route change / permission revoked | Session transitions to FAILED with explicit UI state; partial session exported. |
| Process death mid-session | DB is the source of truth; on restart the app offers session recovery (resume queue drain). |

### 9.3 Observability

In-app status panel (design: connection state, queue depths, last ASR/LLM latencies, call counters, failure counts) plus a per-session plain-text local log (app-private) — both part of the debug facility and removed with it (A-5). No remote telemetry (§7).

### 9.4 Battery & thermal (A-4, risk)

Screen-on + continuous capture + Wi-Fi for 90 minutes is the A50's stress case. Design mitigations: no on-device ML (R-N2), WAV (no encode overhead), screen brightness is the dominant drain — operator guidance: dim screen, charger for long sessions. Validated in Doc 2 §14; residual risk §11.

### 9.5 Debug workflow (validation phase)

The retained first-minutes audio (exact upload bytes — PDD-4) enables the core debugging loop: play the retained WAV ↔ compare against the transcript, statements, speaker tags, and annotations for the same time range. This is the primary instrument for risk 1 (multi-speaker ASR quality) and risk 2 (false-positive annotations).

---

## 10. Key Design Decisions

- **D-1 — Stateless chunked ASR (no streaming).** OpenRouter offers no streaming-ASR socket (CA-1); chunked batch calls are the only mechanism. Consequence: the latency budget (§3.4) with chunk length as the tuning lever. *Rejected alternative:* a streaming STT vendor — violates R-N1; rejected with the operator in interview ([CONFLICT-3] context).
- **D-2 — Two-stage LLM pipeline: segment first, analyze second.** [COMPLEXITY JUSTIFICATION: two calls instead of one per chunk. Justified because it decouples protocol latency from analysis latency (statements render ~2–4 s earlier), isolates failure domains (a malformed analysis response never blocks the protocol), and enforces R-F6 — both analysis families in one call with one cadence, structurally equal. A single combined call couples all three concerns to the slowest and least reliable stage.]
- **D-3 — Claims digest for session-internal consistency.** [COMPLEXITY JUSTIFICATION: R-F7 requires checking claims against statements "earlier in the meeting," not merely the last minutes. Whole-session verbatim context is unbounded (90 min ≈ hundreds of statements). The digest (one-line checkable claims, capped, drop-oldest, maintained by the analysis calls themselves) satisfies R-F7 at bounded cost. *Rejected:* rolling window only — silently under-delivers the accepted scope.]
- **D-4 — Energy-VAD chunking (DSP), not fixed timers, not ML.** [COMPLEXITY JUSTIFICATION: fixed-interval chunks upload silence (cost, latency) and ignore conversational structure; ML VAD violates R-N2. Energy VAD with hangover is simple, deterministic, debuggable DSP.]
- **D-5 — Speaker rename/merge as render-time overlay.** Raw inferred tags are never mutated; mappings apply at render/export. Cheap, reversible, preserves the evidentiary chain (R-F3).
- **D-6 — Provisional statements with in-place consolidation.** Solves statement/chunk misalignment (PDD-1): a statement spanning chunks renders immediately and completes in place; seam-smoothing is part of the segmentation prompt contract.
- **D-7 — 16 kHz mono PCM WAV upload format.** Lossless, trivially "exact bytes" for the debug facility (PDD-4), native ASR input rate (no resample artifacts), ~32 KB/s is negligible on company Wi-Fi (R-N6). *Rejected:* lossy compression — saves bytes that do not matter here, breaks exact-bytes debuggability.
- **D-8 — Room DB as single source of truth; UI as pure projection.** Every stage persists through the repository; UI observes Flows and never networks. This one invariant delivers buffer-and-backfill durability, process-death recovery, and UI consistency (R-F12) without a second mechanism. *Rejected:* WorkManager as a second scheduling substrate — redundant under a live foreground service.
- **D-9 — Retention facility with designed end-state.** Default 10 min, runtime 0–10, 0 = discard-immediately (PDD-3); exact upload bytes, app-private, prune-to-10 (PDD-4). The revert to the end-state (full discard-immediately) is a settings flip; the facility's eventual code removal is tracked as A-5.
- **D-10 — Language locked at session start, injected into every prompt.** Deterministic (one tap), avoids auto-detect failure modes; the lock is passed to the ASR language field and the segmentation/analysis prompt contracts (R-F9).
- **D-11 — Jetpack Compose UI.** Standard modern choice for a scrolling-protocol app; no constraint binds to Views; Compose's `LazyColumn` handles long protocols. Low-stakes decision, easily reversed at Doc 2 if tooling argues otherwise.

---

## 11. Open Questions & Assumptions

### 11.1 Assumptions (each falsifiable, each with localized impact)

| ID | Assumption | If falsified |
|---|---|---|
| A-1 | Neither ASR model returns speaker metadata via OpenRouter (operator's T1 probe pending) | Free labels replace inferred tags; change localized to StatementProcessor + Statement.speakerTag provenance |
| A-2 | The §3.4 latency budget satisfies the near-utterance bar | Chunk length tuned down; if still unsatisfactory, [CONFLICT-2] reopens with the operator — the platform's batch nature is the ceiling |
| A-3 | Android 11 (API 30) minimum; A50 supports it | Raise minSdk if a required API forces it; A50 floor re-checked (R-N4 flag duty) |
| A-4 | A50 sustains 90-min sessions within thermal/battery tolerance | Operational mitigation only (charger, brightness); no architectural fallback in scope |
| A-5 | Retention facility is temporary with a defined end-state | None — commitment tracked, removal path designed (D-9) |
| A-6 | Resolved 2026-11-08: live API requires multipart `/audio/transcriptions` for both configured ASR models | Corrected in this revision and Doc 2 v1.2.0; gateway and future AsrClient use the explicit transcription operation |

### 11.2 Open questions (validation-phase, not design-blocking)

1. **Multi-speaker table audio quality** of both ASR models (risk 1) — answered empirically via the retained-audio debug workflow (§9.5) and the Doc 2 §14 early two-speaker live test. The choice between the two ASR models is an operator setting, not an architectural commitment.
2. **mai-transcribe-2 routing stability on OpenRouter** — recently released model; if routing proves unreliable, whisper-large-v3-turbo is the standing alternative (both are first-class in the design).
3. **False-positive rates of both analysis families** (risk 2) — calibrated during validation by reviewing annotations against retained audio; prompt-contract tuning is the lever, architectural impact nil.
4. **German vs. English quality asymmetry** — both languages carry equal requirement weight (OR-12); per-language quality differences surface in the same validation loop.

### 11.3 Residual risks (carried from the readiness summary, unchanged in rank)

1. Multi-speaker ASR quality untested (mitigation: §9.1 placement, §9.5 workflow, §11.2 Q1).
2. False-positive annotations from both analysis families (mitigation: observation-not-verdict phrasing contract, §6.1c).
3. Latency budget vs. freshness bar (A-2; [CONFLICT-2] residual).
4. Inferred speaker tags degrade at 4+ speakers (accepted via OR-3 demotion; visible marking).
5. Statement consolidation complexity (D-6; bounded by prompt contract + in-place updates).
6. Battery/thermal on A50 (A-4; §9.4).
7. OpenRouter rate limits/outages (bounded by queueing; annotations survive without loss — §3.3).

---

