# Document 3 — Implementation Roadmap

**Title:** Nitpicker — Implementation Roadmap
**Version:** 1.3.0
**Date:** 2026-11-08
**Status:** Approved — operator-authorized ASR model replacement (supersedes v1.2.0)
**Baseline:** Doc 1 v1.2.0 (Design Document, endpoint revision approved 2026-11-08); Doc 2 v1.3.0 (Technical Specification, endpoint revision, 2026-11-08).
**Reference binding:** In this document, "Doc 1" ≡ Doc 1 v1.2.0 and "Doc 2" ≡ Doc 2 v1.3.0. All section citations resolve against these versions. Version pins are stated explicitly wherever the citation is load-bearing (§3, §5, §6, §7).
**Prepared per:** Multiphase Interview Tech Docs workflow v1.2.0.

**Revision History**

| Version | Date | Changes |
|---|---|---|
| 1.0.0 | 2026-10-04 | Initial draft, 22 work packages. |
| 1.1.0 | 2026-10-04 | Re-audited against the fully re-delivered Doc 2 v1.1.0 and re-issued (superseding the withdrawn "remains valid without changes" claim): (1) reference binding established — all cross-references now resolve to Doc 1 v1.0.0 / Doc 2 v1.1.0 per the workflow's cross-citation rule; (2) WP-05 task list aligned to the complete SettingsStore public surface of Doc 2 v1.1.0 §4.1; (3) WP-11/WP-12 terminal flows now cite `settings.snapshot()` (seam S30 of Doc 2 v1.1.0 §15.1); (4) WP-17 scope and the §6 matrix name MainActivity/AppNav explicitly (Doc 2 v1.1.0 §2, §8.14); (5) WP-19 contracts name `SessionLog.deleteForSession` (Doc 2 v1.1.0 §8.13); (6) completion-criteria seam audit extended to S1–S30. No work package added, removed, or reordered; dependency graph unchanged. |

| 1.2.0 | 2026-11-08 | Re-audited against Doc 1 v1.1.0 and Doc 2 v1.2.0 after operator-authorized ASR endpoint correction. WP-06/07/09/10 contracts now name typed multipart transcriptions and cross-endpoint scripted ordering; S7 and T2 use `postTranscription`. All 22 WP boundaries, dependency edges, milestones and remaining seam IDs are unchanged. |

| 1.3.0 | 2026-11-08 | Re-audited against Doc 1 v1.2.0 and Doc 2 v1.3.0 for the authorized MAI/Voxtral 3 model selection and legacy-selection recovery. Existing ASR, settings, recovery, gateway, and live-test packages consume these changes; all 22 WP boundaries, dependencies, milestones and S1–S30 seam identities remain unchanged. |

---

## 1. Roadmap Overview

**Strategy.** Bottom-up in dependency order: build skeleton → data layer → pure logic (DSP, prompts, gateway, clients) → pipeline orchestration → export → service → UI → integration tests → on-device acceptance. Every WP ends with a building, test-green state; no WP leaves the repo broken.

**Phase sequence.**

- **P1 — Foundation** (WP-01…03): build skeleton, domain types, Room layer, repository.
- **P2 — Core logic** (WP-04…14): DSP, settings, prompts/DTOs/parser, gateway, support modules, test doubles, LLM clients, orchestrator (split I/II), export, capture engine.
- **P3 — Service & composition** (WP-15…16): AppContainer, SessionService.
- **P4 — UI** (WP-17…19): scaffold + Start + Settings; Live + StatusPanel; List + Detail.
- **P5 — Verification & release** (WP-20…22): integration harness + TC-PIPE-1…5; TC-PIPE-6…9; acceptance + release.

**Consumption model.** The coding agent executes **one work package per session, strictly in dependency order**. Session inputs: Doc 1 v1.2.0, Doc 2 v1.3.0, this document (Doc 3 v1.3.0), and the repo at `main` with all dependency WPs merged. The agent never edits the spec documents; discoveries are reported as `[CONFLICT]`/`[ASSUMPTION]` in the WP report, never silently designed around. If any spec document is revised, this roadmap is re-audited and re-issued before further WPs execute (baseline-propagation rule, per this document's own revision history).

## 2. Global Conventions

**Repo bootstrap (WP-01).** Git init → Gradle skeleton → version pins → manifest → `.gitignore` (incl. `keystore.properties`) → initial commit. All later WPs commit on top.

**Branch/commit.** One branch per WP: `wp/<nn>-<slug>` off `main`. Conventional commits (`feat:`, `test:`, `fix:`, `chore:`), at least one commit per task cluster. Merge to `main` only when the WP's verification is green.

**Versioning of delivered artifacts.** APK `versionName` is semver, bumped at each milestone (M1 → 0.1.0 … M6 → 1.0.0); `versionCode` monotonic per release build. Docs 1–3 are the spec of record — versioned revisions only, on operator request.

**Definition of done (every WP).**
1. All task-list items complete; files touched exactly as scoped (no scope creep, none beyond Doc 2 §1 scope).
2. `./gradlew :app:testDebugUnitTest` green; `./gradlew :app:assembleDebug` green.
3. Every contract produced is consumed by a named later WP or by runtime (caller existence).
4. WP report written to `docs/wp-reports/WP-xx.md`: what was built, verification output summary, and any `[ASSUMPTION]`/`[CONFLICT]` flags. *WP reports are roadmap process artifacts, not application scope.*
5. Deviations from spec are **reported, never silently applied**; structural discoveries trigger a Doc 2 revision request, not local invention.

**Test-construction rule (restated, binding).** Pre-existing state is established by calling the production path, never by direct fixture writes. DAO/repository tests may write through the DAO/repository itself (that *is* the code under test); domain state (statements, annotations, jobs) is only ever produced by driving the pipeline.

## 3. Dependency Graph

Acyclic; verified by inspection (every WP depends only on lower numbers). All spec surfaces consumed are those of **Doc 2 v1.3.0**.

| WP | Title | Depends on | Parallelizable with |
|---|---|---|---|
| WP-01 | Repo bootstrap & build skeleton | — | — |
| WP-02 | Domain types + Room layer | WP-01 | — |
| WP-03 | SessionRepository | WP-02 | WP-04, WP-05, WP-06 |
| WP-04 | Audio DSP (Chunker/VAD, WavEncoder) | WP-02 | WP-03, WP-05, WP-06 |
| WP-05 | SettingsStore | WP-01 | WP-03, WP-04, WP-06 |
| WP-06 | Prompts + ChatModels + LlmJsonParser | WP-02 | WP-03, WP-04, WP-05 |
| WP-07 | ChatTransport + OpenRouterGateway | WP-06 | WP-08 |
| WP-08 | Support modules (retention, log, metrics, connectivity) | WP-02, WP-05 | WP-07 |
| WP-09 | Shared test doubles | WP-07, WP-08 | — |
| WP-10 | LLM clients (Asr, StatementProcessor, AnalysisEngine) | WP-06, WP-07, WP-09 | WP-13, WP-14 |
| WP-11 | Orchestrator I (ASR + segmentation workers) | WP-03, WP-05, WP-08, WP-09, WP-10 | WP-13, WP-14 |
| WP-12 | Orchestrator II (analysis worker, terminal flow, recovery, drain) | WP-11 | WP-13, WP-14 |
| WP-13 | Export layer | WP-03 | WP-10…12, WP-14 |
| WP-14 | AudioCaptureEngine | WP-02, WP-04 | WP-10…13 |
| WP-15 | Composition root (AppContainer + CompanionApp) | WP-03, WP-05, WP-07, WP-08, WP-10, WP-13 | WP-14 |
| WP-16 | SessionService | WP-12, WP-13, WP-14, WP-15 | — |
| WP-17 | UI scaffold + Start + Settings | WP-15, WP-16 | — |
| WP-18 | LiveScreen + StatusPanel | WP-16, WP-17 | WP-19 |
| WP-19 | ListScreen + DetailScreen | WP-16, WP-17 | WP-18 |
| WP-20 | Integration harness + TC-PIPE-1…5 | WP-03, 04, 08, 09, 10, 11, 12, 13 | WP-17…19 |
| WP-21 | Integration TC-PIPE-6…9 | WP-20 | WP-17…19 |
| WP-22 | On-device acceptance + release | WP-16…WP-21 (all) | — |

**Contract-closure audit of verification requirements:** every capability a WP's verification needs is produced by an earlier WP, defined in Doc 2 v1.3.0, or explicitly operator-provided (credentials, hardware, human speakers — WP-18/M3 and WP-22, stated there). No WP's verification consumes an unreachable module, hand-built state, or an unspecified credential.

## 4. Work Packages

Format per WP: objective/rationale · scope · spec refs · tasks · contracts (consumed → produced) · acceptance · verification · sizing (S/M/L).

---

**WP-01 — Repo bootstrap & build skeleton** · P1 · dep — · **S**
*Objective:* a building, installable empty app with the pinned toolchain — the base every later WP commits onto.
Scope: `settings.gradle.kts`, root + app `build.gradle.kts`, `gradle/libs.versions.toml` (exact pins per Doc 2 §3 floors), `app/src/main/AndroidManifest.xml` (all permissions of Doc 2 §7; service declaration deferred to WP-16), `CompanionApp.kt` (minimal), `ui/MainActivity.kt` (placeholder content), `.gitignore`. Out of scope: everything else in Doc 2 §2.
Spec refs: Doc 2 §2, §3, §7, §13.
Tasks: (1) git init; (2) Gradle skeleton, Kotlin 2.0, Compose BOM, minSdk 30, targetSdk current stable; (3) pin all dependency versions in the version catalog; (4) manifest with permission set; (5) minimal Application + MainActivity; (6) `.gitignore` incl. `keystore.properties`; (7) initial commit.
Contracts: consumed Doc 2 §3 floors → produced buildable `:app` module, manifest, `MainActivity`.
Acceptance: `assembleDebug` succeeds; APK installs and launches an empty screen on a device/emulator.
Verification: `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL; `adb install -r …` → launches without crash.

---

**WP-02 — Domain types + Room layer** · P1 · dep WP-01 · **M**
*Objective:* the complete shared type vocabulary and persistence layer — the substrate every module compiles against.
Scope: `domain/Types.kt`, `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/AppDatabase.kt`; schema JSON committed. Out of scope: repository, any business logic.
Spec refs: Doc 2 §5.1 (DDL verbatim: CHECKs, UNIQUEs, indices, `segmentation_pending` join), §5.2 (DAO signatures), §8.0.
Tasks: (1) all §8.0 types incl. `SettingsSnapshot`; (2) 7 entity classes mapping the DDL 1:1; (3) DAO interfaces with transactional claim semantics (`claimNextForAsr`, `claimNextForSegmentation` idempotent-guard, `claimNextPending`); (4) `AppDatabase` v1, `exportSchema = true`; (5) DAO round-trip tests (insert/claim/state-transition via DAOs — the DAO is the code under test).
Contracts: consumed Doc 2 §5/§8.0 → produced all entities, DAOs, `AppDatabase`, domain types.
Acceptance: DDL fidelity (constraints and indices present in schema JSON); claim operations are transactional and re-claim-safe.
Verification: `./gradlew :app:testDebugUnitTest --tests "com.nitpicker.data.db.*"` green.

---

**WP-03 — SessionRepository** · P1 · dep WP-02 · **L**
*Objective:* the sole DAO caller and production write path for every domain table — the piece that makes the test-construction rule enforceable.
Scope: `data/repository/SessionRepository.kt`. Out of scope: any caller.
Spec refs: Doc 2 §5.3 (full API), §5.2 semantics.
Tasks: (1) simple delegating methods with `@Transaction`; (2) claim wrappers preserving transactional CAS; (3) `applySegmentation` complete logic — continuation → `extendStatement` + REANALYSIS-job rule; else insert statements + finalize prior PROVISIONAL when `continuesPrevious=false`; always insert CHUNK job; (4) `saveAnalysis` — delete+replace annotations, append claims, cap enforcement, job DONE, chunk ANALYZED; (5) `claimNextAnalysisJob` context assembly (targets fresh-loaded, window, digest, language); (6) `sessionForExport` read model; (7) `queueDepthsFlow`; (8) repository tests: state created only via repository methods.
Contracts: consumed DAOs (WP-02) → produced `SessionRepository` API.
Acceptance: every §5.3 method behaves per spec under test; `applySegmentation` handles all four continuation/fresh × provisional/final combinations.
Verification: `--tests "…data.repository.*"` green. Sizing note: if the session overflows, split commit-wise (read/delegate methods first, composition methods second) within the same WP; scope unchanged.

---

**WP-04 — Audio DSP: Chunker/VAD + WavEncoder** · P2 · dep WP-02 · **M**
*Objective:* deterministic, non-ML chunking and byte-exact WAV encoding — the front door of the pipeline.
Scope: `audio/Chunker.kt` (+VAD parameters), `audio/WavEncoder.kt`. Out of scope: AudioRecord (WP-14).
Spec refs: Doc 2 §8.2 (VAD pseudocode, parameters), §8.3.
Tasks: (1) 20 ms frame RMS + EMA noise floor (α=0.05), threshold `max(floor×8, fixed)`; (2) silence close (`vad_silence_ms`), max-length close (`chunk_max_sec`), min-speech merge (`MIN_SPEECH_MERGE_MS`); (3) hangover state machine per pseudocode; (4) `WavEncoder` 44-byte RIFF header + PCM; (5) unit tests: silence close, max close, merge, threshold hysteresis, byte-exact WAV for fixed input.
Contracts: consumed `PcmFrame`, `AudioChunkData` (WP-02) → produced `Chunker`, `WavEncoder`.
Acceptance: all boundary tests green; WAV output byte-identical for identical input (determinism — required by the debug facility's exact-bytes property).
Verification: `--tests "…audio.*"` green.

---

**WP-05 — SettingsStore** · P2 · dep WP-01 · **M**
*Objective:* settings persistence with Keystore-encrypted API key — the system's only secret handling.
Scope: `data/settings/SettingsStore.kt`. Out of scope: settings UI (WP-17).
Spec refs: Doc 2 §4.1 (**complete public surface**: `apiKeySet: Flow<Boolean>`, `apiKey(): String?`, `snapshot(): SettingsSnapshot`, five typed setters), §7, §8.0.
Tasks: (1) DataStore keys per §4.1; (2) AES-256-GCM via Android Keystore (StrongBox when available), ciphertext+IV storage; (3) `apiKeySet` flow and `apiKey()` decryption (callers: Start gating, gateway key provider); (4) `snapshot(): SettingsSnapshot` — typed producer for orchestrator workers (seam S30); (5) the five typed setters with clamping validation (retention 0–10, chunk 5–15, VAD 400–2000); (6) tests: key round-trip, snapshot round-trip, clamp behavior, no plaintext in persisted file (AC-11 seed).
Contracts: consumed — → produced `SettingsStore` full public surface, `SettingsSnapshot` producer.
Acceptance: every member of the §4.1 signature block exists and has its named caller; key survives process restart encrypted; invalid values clamp, never crash.
Verification: `--tests "…data.settings.*"` green (Robolectric for Keystore).

---

**WP-06 — Prompts + ChatModels + LlmJsonParser** · P2 · dep WP-02 · **M**
*Objective:* the three prompt contracts, chat/transcription DTOs and the JSON discipline — where R-F6 (equal standing) and CA-5 (internal knowledge only) are enforced as testable text.
Scope: `prompts/Prompts.kt`, `llm/ChatModels.kt`, `llm/LlmJsonParser.kt`. Out of scope: any network.
Spec refs: Doc 2 §6.2 (DTOs), §6.4–6.5 (payload schemas), §8.7 (prompt text verbatim).
Tasks: (1) `Prompts` — three builders exactly per §8.7, language-parameterized; (2) DTOs per §6.2 incl. `JsonElement` content for audio parts; (3) parser: fenced JSON, trailing text tolerance, typed decode; (4) tests: **prompt contract tests** — equal-priority clause present, internal-knowledge-only clause present, language injected, both families in the response schema, verbatim-quote rule present; parser tests incl. repair-path input classification.
Contracts: consumed `Language` etc. (WP-02) → produced `Prompts`, DTOs, `LlmJsonParser`.
Acceptance: prompt-content tests green — the co-equality requirement (R-F6) is now a regression-guarded artifact.
Verification: `--tests "…prompts.*" --tests "…llm.LlmJsonParser*"` green.

---

**WP-07 — ChatTransport + OpenRouterGateway** · P2 · dep WP-06 · **M**
*Objective:* the single HTTP egress to OpenRouter with retry/timeout/error typing — the only network code in the app.
Scope: `llm/ChatTransport.kt`, `llm/OpenRouterGateway.kt`. Out of scope: clients (WP-10).
Spec refs: Doc 2 §4.2 (retry, timeouts), §6.1, §6.6 (error semantics), D-12.
Tasks: (1) interface (`postChat` and `postTranscription`) + `GatewayConfig` (constructor-injectable base URL for tests); (2) OkHttp client, chat JSON and transcription multipart encoding, exact WAV bytes, language/prompt fields, auth header, attribution headers; (3) retry policy: 3 attempts, 2/8/32 s ±20 % jitter, 429 honors `Retry-After` ≤60 s; 401/403 no-retry; (4) typed exceptions; (5) MockWebServer tests: 429+Retry-After honored, 500→retry→success, 401 no-retry, timeout, valid chat/transcription response parse, exact multipart bytes, transcription retry/auth/routing errors.
Contracts: consumed DTOs (WP-06) → produced `ChatTransport`, `OpenRouterGateway`, typed exceptions.
Acceptance: error-semantics table of §6.6 reproduced exactly under test.
Verification: `--tests "…llm.OpenRouterGateway*"` green (MockWebServer).

---

**WP-08 — Support modules** · P2 · dep WP-02, WP-05 · **M**
*Objective:* the four cross-cutting facilities every worker uses — retention, logging, metrics, connectivity.
Scope: `audio/AudioRetentionStore.kt`, `log/SessionLog.kt`, `pipeline/ConnectivityMonitor.kt`, `pipeline/PipelineMetrics.kt`. Out of scope: their callers' behavior.
Spec refs: Doc 2 §4.2 (`RETAINED_SESSIONS_KEEP`, `LOG_FILE_CAP`), §8.4, §8.13 (**incl. `SessionLog.deleteForSession()`**).
Tasks: (1) retention: window rule (`startedAtOffsetMs < retentionMinutes×60 000`), file layout `retained/session-{id}/chunk-{seq}.wav`, prune keep-10, `deleteForSession`, `filesForSession`; (2) log: `[+mm:ss] LEVEL TAG msg`, 512 KB head-truncation, `deleteForSession` (caller: DetailViewModel, WP-19); (3) `SystemConnectivityMonitor` (ConnectivityManager callback → `StateFlow<NetworkState>`); (4) `PipelineMetrics` (snapshot `StateFlow`, `update`); (5) tests: window boundary, prune ordering, 0-minute mode, truncation.
Contracts: consumed `AudioChunkRow`, `SettingsSnapshot` (WP-02/05) → produced the four modules.
Acceptance: retention never writes outside the window; prune leaves exactly ≤10 newest session dirs.
Verification: `--tests "…audio.AudioRetentionStore*" --tests "…log.*"` green.

---

**WP-09 — Shared test doubles** · P2 · dep WP-07, WP-08 · **S**
*Objective:* the scripted transport and fake network gate all later test WPs share — built once, after the interfaces they fake exist.
Scope: `test/…/fakes/ScriptedChatTransport.kt`, `test/…/fakes/FakeNetworkGate.kt`. Out of scope: `InMemoryDocumentSink` (WP-13 — belongs to the export layer).
Spec refs: Doc 2 §12 (harness description).
Tasks: (1) scripted transport: one sequential typed chat/transcription response/error script, request recording across both endpoints, call counting; (2) fake gate: manual `NetworkState` toggle; (3) trivial behavior tests of the doubles themselves.
Contracts: consumed `ChatTransport` (WP-07), `ConnectivityMonitor` (WP-08) → produced both doubles.
Acceptance: doubles are deterministic and record enough state for order assertions.
Verification: `--tests "…fakes.*"` green.

---

**WP-10 — LLM clients** · P2 · dep WP-06, WP-07, WP-09 · **M**
*Objective:* the three OpenRouter call shapes (multipart ASR plus chat segmentation/analysis) — ASR, segmentation, analysis — including validation and the one-repair policy.
Scope: `llm/AsrClient.kt`, `llm/StatementProcessor.kt`, `llm/AnalysisEngine.kt`. Out of scope: workers/queueing (WP-11/12).
Spec refs: Doc 2 §6.3, §6.4, §6.5, §8.6, §8.8, §8.12.
Tasks: (1) `AsrClient.transcribe` — build `TranscriptionRequest` with model, exact WAV, session language and verbatim prompt; call `postTranscription`; blank `text` → `MalformedLlmOutputException`; (2) `StatementProcessor.segment` — context JSON build, parse, one repair re-prompt, second failure → exception; (3) `AnalysisEngine.analyze` — context build from `AnalysisJobContext`, parse, validation: family enum else drop, `statementSeq` ∈ targets else drop, quote not a verbatim substring → drop quote keep note; (4) tests with `ScriptedChatTransport`: happy paths, repair path, all three validation drops.
Contracts: consumed transport, prompts, parser, doubles → produced the three clients.
Acceptance: validation rules of §6.5 reproduced exactly; repair invoked at most once per call.
Verification: `--tests "…llm.AsrClient*" "…llm.StatementProcessor*" "…llm.AnalysisEngine*"` green.

---

**WP-11 — Orchestrator I: ASR + segmentation workers** · P2 · dep WP-03, 05, 08, 09, 10 · **L**
*Objective:* the first two worker coroutines with gating, FIFO claiming, retry, and terminal-failure handling.
Scope: `pipeline/PipelineOrchestrator.kt` (created). Out of scope: analysis worker, recovery, drain (WP-12).
Spec refs: Doc 2 §8.9 (workers, gate, retry; `settings.snapshot()` for retention calls — seam S30), §9 (job table), T2/T4 traces.
Tasks: (1) class shell, `startWorkers` scope; (2) asrWorker: claim → gate check → `transcribe` → `saveTranscript`; retry with `attempt_count`, re-queue ≤3, terminal → FAILED + `retention.retainIfNeeded(..., settings.snapshot())` debug copy + `nullBlob`; (3) segmentationWorker: claim → tail context load (`lastStatement`, `recentTags(6)`) → `segment` → `applySegmentation`; same retry policy, terminal → gap; (4) workers park on `OFFLINE` before any transport call; (5) tests: FIFO claim order, gate parking (no transport calls while offline), retry-then-success, terminal path with retained debug file.
Contracts: consumed repo, clients, doubles, gate, metrics, log, `SettingsStore.snapshot()` → produced `PipelineOrchestrator` (asr/seg workers).
Acceptance: no network call occurs while gate is OFFLINE; strict seq order preserved under backfill.
Verification: `--tests "…pipeline.PipelineOrchestrator*"` green (subset for this WP).

---

**WP-12 — Orchestrator II: analysis worker, terminal flow, recovery, drain** · P2 · dep WP-11 · **M**
*Objective:* the analysis worker, the audio-blob terminal flow, REANALYSIS replacement, in-flight recovery, and drain completion — completing the pipeline core.
Scope: `pipeline/PipelineOrchestrator.kt` (modified). Out of scope: service (WP-16).
Spec refs: Doc 2 §8.4 (file-then-DB order), §8.9 (REANALYSIS, `drainComplete`, `recoverInFlight`; `settings.snapshot()` at terminal flow — seam S30), T3, T5, T6.
Tasks: (1) analysisWorker: claim ctx → `analyze` → `saveAnalysis`; (2) CHUNK-job terminal: chunk ANALYZED → `retainIfNeeded(..., settings.snapshot())` (write file) → `markDiscarded` → `nullBlob`, idempotent under crash-retry; (3) REANALYSIS jobs flow through the same worker (delete+replace via `saveAnalysis`); (4) `recoverInFlight` (both resets); (5) `drainComplete` StateFlow = asr queue ∧ analysis queue ∧ segmentation pending all empty; (6) tests: analysis happy path, REANALYSIS replaces annotations wholesale, recovery resets, drain transitions, file-then-DB idempotency.
Contracts: consumed repo, retention, clients, `SettingsStore.snapshot()` → produced complete `PipelineOrchestrator`.
Acceptance: a crash between file write and `markDiscarded` leaves state that the next terminal transition resolves without loss or duplication.
Verification: `--tests "…pipeline.PipelineOrchestrator*"` green (full).

---

**WP-13 — Export layer** · P2 · dep WP-03 · **M**
*Objective:* Markdown rendering and the two document sinks — the durable deliverable of every session.
Scope: `export/DocumentSink.kt` (interface + `MediaStoreDocumentsSink`), `export/MarkdownExporter.kt`, `test/…/fakes/InMemoryDocumentSink.kt`. Out of scope: export triggers (service/UI).
Spec refs: Doc 2 §6.3 (template, filename, gap markers), §8.11, §8.14 mapping rules.
Tasks: (1) interface + MediaStore impl (Documents, `Nitpicker/`, `text/markdown`); (2) renderer: header block, timestamped speaker lines with mapping overlay and `?` suffix, italic family-prefixed annotations, transcription/recording gap markers, filename `protocol-YYYY-MM-DD-HHmm-{DE|EN}.md`; (3) `ExportModel` assembly via `repo.sessionForExport`; (4) tests: pure rendering units (filename, timestamp format, gap markers); mapping resolution tested with mappings created via `repo.upsertMapping` (production path); full-session rendering deferred to WP-20 TC-PIPE-4.
Contracts: consumed repo, `SpeakerMapping` semantics → produced `MarkdownExporter`, `DocumentSink`, both sinks.
Acceptance: mapped names replace raw tags at render; raw tags remain untouched in DB (D-5).
Verification: `--tests "…export.*"` green.

---

**WP-14 — AudioCaptureEngine** · P2 · dep WP-02, WP-04 · **M**
*Objective:* the AudioRecord read loop feeding the Chunker — the hardware boundary.
Scope: `audio/AudioCaptureEngine.kt`. Out of scope: wiring to repository (WP-16).
Spec refs: Doc 2 §8.2 (loop, `MicLostException`, stop-drain).
Tasks: (1) AudioRecord 16 kHz/mono/16-bit, 320-sample reads, dedicated IO coroutine; (2) frame delivery to `Chunker.offer`; (3) `stop()` drains chunker tail into a final chunk via `onChunkClosed`; (4) fatal read errors/route loss → `MicLostException`; (5) resource release.
Contracts: consumed `Chunker` (WP-04), types → produced `AudioCaptureEngine`.
Acceptance: compiles; stop-drain logic reviewed against spec. *Per Doc 2 §12, runtime behavior is verified on-device (M3) — automated verification for this WP is build-only by design, not by omission.*
Verification: `./gradlew :app:assembleDebug` + full unit suite still green.

---

**WP-15 — Composition root** · P3 · dep WP-03, 05, 07, 08, 10, 13 · **S**
*Objective:* `AppContainer` — the single module that constructs infrastructure, and the app entry exposing it.
Scope: `di/AppContainer.kt`, `CompanionApp.kt` (modify). Out of scope: session-scoped construction (service's job, WP-16).
Spec refs: Doc 2 §15 (construction order verbatim), §8.1.
Tasks: (1) `AppContainer` in §15 order: SettingsStore → SystemConnectivityMonitor → AppDatabase/SessionRepository → AudioRetentionStore → ChatTransport/Gateway (key provider = settings) → AsrClient → StatementProcessor → AnalysisEngine → PipelineMetrics → SessionLog → MarkdownExporter(+MediaStoreDocumentsSink); (2) `CompanionApp` constructs and exposes it; (3) Robolectric smoke: container builds, all singletons non-null, gateway bound to settings provider.
Contracts: consumed all infrastructure WPs → produced `AppContainer` (the composition root).
Acceptance: no module outside `AppContainer` (and the sanctioned `SessionService` exception, §15) constructs infrastructure.
Verification: `--tests "…di.*"` green; build green.

---

**WP-16 — SessionService** · P3 · dep WP-12, 13, 14, 15 · **L**
*Objective:* the foreground service owning lifecycle, locks, wiring, and the stop→drain→export transition.
Scope: `service/SessionService.kt`, manifest service declaration + FGS type attribute (modify manifest). Out of scope: UI.
Spec refs: Doc 2 §8.10 (actions, locks, stop path, force-finalize, recovery), §7 (FGS start), §9, T1, T5, T6.
Tasks: (1) intents ACTION_START/STOP/RESUME/FORCE_FINALIZE; (2) `startForeground` MICROPHONE via ServiceCompat; silenced channel ([CONFLICT-1] resolution); (3) partial wake lock + Wi-Fi lock; (4) wiring: capture engine → `WavEncoder.toWav` → `repo.enqueueChunk`; (5) `recoverInFlight` + `startWorkers` on start/resume; prune at start; (6) stop path: STOPPING → capture stop → drain → `finalizeSession` → export → COMPLETED + `setExportUri`; (7) 120 s stall → force-finalize path (export partial, remaining FAILED); (8) resume path reconstructs engine/orchestrator from DB.
Contracts: consumed orchestrator, engine, exporter, repo, retention, container → produced the four service intents (consumed by WP-17…19).
Acceptance: build green; stop path compiles against `drainComplete`. *Runtime verification at M3 (needs microphone + credential — operator-provided).*
Verification: `./gradlew :app:assembleDebug` + full unit suite green.

---

**WP-17 — UI scaffold + Start + Settings** · P4 · dep WP-15, 16 · **M**
*Objective:* navigation scaffold, the start flow with its gates, and the settings surface.
Scope: `ui/navigation/AppNav.kt`, `ui/MainActivity.kt` (modify: host AppNav), `ui/StartScreen.kt` + `StartViewModel.kt`, `ui/SettingsScreen.kt` + `SettingsViewModel.kt`, theme/scaffold files within `ui/`. Out of scope: Live/List/Detail.
Spec refs: Doc 2 §2 (tree: MainActivity, AppNav), §8.14 (host/nav row, Start gating, Settings fields), §4.1 (settings surface and clamps).
Tasks: (1) `MainActivity` as Compose host mounting `AppNav`; nav graph Start ↔ Live ↔ List ↔ Detail ↔ Settings; (2) Start: De/En toggle, Start disabled until key set (`SettingsStore.apiKeySet`) + RECORD_AUDIO granted, POST_NOTIFICATIONS request on 13+ (denial hides notification, recording continues — spec'd behavior); (3) `start(language)` → ACTION_START; (4) Settings: model, retention, chunk, VAD fields + write-only key, via the five typed setters; (5) ViewModels constructor-injected from container.
Contracts: consumed container, `SettingsStore` public surface, service intents → produced navigation host + two screens.
Acceptance: gating enforced; settings writes clamp via WP-05 rules; every §4.1 setter reachable from the UI.
Verification: build green; manual emulator nav smoke (no mic needed for nav).

---

**WP-18 — LiveScreen + StatusPanel** · P4 · dep WP-16, 17 · **L**
*Objective:* the executive's live view — the product's face and the M3 first-light centerpiece.
Scope: `ui/LiveScreen.kt` + `LiveViewModel.kt`, `ui/common/StatusPanel.kt`. Out of scope: session list/detail.
Spec refs: Doc 2 §8.14 (Live row: flows, keep-screen-on, auto-scroll/pause/jump, provisional `…`, italics, Stop, STOPPING + force-finalize, StatusPanel), §10.
Tasks: (1) combined `Flow<List<StatementView>>` from statements + annotations + mappings; (2) `PipelineSnapshot` StateFlow → collapsible StatusPanel; (3) `FLAG_KEEP_SCREEN_ON`; auto-scroll with pause-on-scroll-up and jump-to-latest chip; (4) provisional trailing `…`; annotations as italic family-prefixed lines; (5) Stop → ACTION_STOP; STOPPING state with Force-finalize button; (6) `StatementView`/`AnnotationView` mapping (Doc 2 §8.14).
Contracts: consumed repo flows, metrics, service intents → produced LiveScreen.
Acceptance: build green. **M3 smoke (operator, on-device, real API key):** start DE session → converse 2 min, two voices → statements appear ≤10 s → annotations ≤14 s → stop → export file exists in Documents/Nitpicker → protocol complete.
Verification: `./gradlew :app:assembleDebug`; M3 smoke procedure executed and recorded in `docs/wp-reports/WP-18.md`.

---

**WP-19 — ListScreen + DetailScreen** · P4 · dep WP-16, 17 · **M**
*Objective:* session history, recovery entry point, and post-session curation (rename/merge/re-export/delete).
Scope: `ui/ListScreen.kt` + `ListViewModel.kt`, `ui/DetailScreen.kt` + `DetailViewModel.kt`. Out of scope: Live behavior changes.
Spec refs: Doc 2 §8.14 (List: flow, Recover; Detail: read-only protocol, rename, merge-with-unmapped-target rule, re-export, delete semantics incl. `SessionLog.deleteForSession`).
Tasks: (1) List: `sessionsFlow`, Recover for ACTIVE/STOPPING → ACTION_RESUME; (2) Detail: read-only protocol (same rendering as Live); (3) rename/merge with validation (merge target must be unmapped); (4) re-export → `MarkdownExporter.export`; (5) delete: `repo.deleteSession` + `retention.deleteForSession` + `log.deleteForSession`; exported files untouched.
Contracts: consumed repo, `AudioRetentionStore.deleteForSession`, `SessionLog.deleteForSession`, exporter → produced both screens.
Acceptance: delete removes DB rows + retained audio + logs, leaves exports; merge validation enforced.
Verification: build green; unit tests for merge validation; **M4 smoke:** full manual pass start→stop→list→detail→rename→merge→re-export on device.

---

**WP-20 — Integration harness + TC-PIPE-1…5** · P5 · dep WP-03, 04, 08, 09, 10, 11, 12, 13 · **L**
*Objective:* the scripted-transport end-to-end harness and the first five pipeline integration tests — the automated proof the pipeline runs.
Scope: `test/…/harness/PipelineHarness.kt`, test classes TC-PIPE-1…5. Out of scope: TC-6…9 (WP-21).
Spec refs: Doc 2 §12 (harness construction, PCM synthesis, test-construction rule).
Tasks: (1) harness: real repo (in-memory Room) + real Chunker/WavEncoder/retention/exporter + real workers + `ScriptedChatTransport` + `FakeNetworkGate` + `InMemoryDocumentSink`; PCM generator (silence + speech-energy bursts) driven through `Chunker.offer`; (2) TC-PIPE-1 happy path (chunk → transcript → statements + annotations + claims → DISCARDED, blob per retention config); (3) TC-PIPE-2 continuation (extend in place + REANALYSIS replaces); (4) TC-PIPE-3 offline backfill (in-order drain, late annotations attach); (5) TC-PIPE-4 stop → drain → export (content match, STOPPING→COMPLETED); (6) TC-PIPE-5 process-death recovery (second container on same DB, in-flight reset, drain completes).
Contracts: consumed all pipeline WPs + doubles → produced the harness (reused by WP-21).
Acceptance: all five TCs green; **no test seeds domain rows directly** (rule compliance is itself reviewable in the diff).
Verification: `--tests "…integration.TCPipe1*" … "…integration.TCPipe5*"` green.

---

**WP-21 — Integration TC-PIPE-6…9** · P5 · dep WP-20 · **M**
*Objective:* the remaining four integration tests — failure, retention, finalization, mapping.
Scope: test classes TC-PIPE-6…9 on the WP-20 harness (harness extensions if needed — additive only).
Spec refs: Doc 2 §12.
Tasks: (1) TC-PIPE-6 ASR terminal failure → FAILED chunk → gap marker in export, pipeline continues; (2) TC-PIPE-7 retention windows (2-min config → only in-window files; prune keeps 10; 0-minute → no files); (3) TC-PIPE-8 provisional finalization at session end; (4) TC-PIPE-9 speaker mapping rename + merge → export reflects, raw tags untouched.
Contracts: consumed harness (WP-20) → produced complete §12 integration suite.
Acceptance: full `testDebugUnitTest` green end to end — **M5**.
Verification: `./gradlew :app:testDebugUnitTest` → all green.

---

**WP-22 — On-device acceptance + release** · P5 · dep WP-16…21 (all) · **M**
*Objective:* execute the Doc 2 v1.3.0 §14 acceptance criteria on real hardware and produce the signed release APK — the project's definition of done.
Scope: release signing config in `app/build.gradle.kts` (`keystore.properties`, git-ignored), acceptance execution + result recording in `docs/wp-reports/WP-22.md`. Out of scope: new features; any fix beyond defect correction.
Spec refs: Doc 2 §13 (release build), §14 (AC-1…11 verbatim), Doc 1 §9.
Tasks: (1) release signing wiring; (2) `assembleRelease` → signed APK; (3) execute AC-1…11 per their §14 procedures (AC-1/2 via session-log ↔ retained-audio comparison; AC-8 two-speaker DE/EN session with annotation review against retained audio); (4) record results; defects → patch commits on the WP branch → re-run affected ACs; structural discoveries → `[CONFLICT]` report, not local redesign; (5) final versionName 1.0.0.
Contracts consumed (runtime-capability form): operator-provided OpenRouter API key (credential), target device (A50-class), two human speakers (AC-8), company Wi-Fi (AC-2). Produced: signed release APK + completed AC record — **M6**.
Acceptance: every AC-1…11 verified or explicitly waived by the operator in the report; `assembleRelease` succeeds.
Verification: `./gradlew :app:assembleRelease` → signed APK installs over previous builds (same key); AC table complete.

---

## 5. Milestones & Integration Checkpoints

| Milestone | After | System state | Smoke procedure |
|---|---|---|---|
| **M1** | WP-01 | Builds, installs, launches empty | `assembleDebug`; install; launch; no crash |
| **M2** | WP-15 | All infrastructure composed; every unit/gateway test green | full `testDebugUnitTest`; container smoke test |
| **M3** | WP-18 | **First light:** full live loop on device — mic → protocol → annotations → export | operator procedure in WP-18 (real key, 2-voice session, latency eyeball vs. AC-1) |
| **M4** | WP-19 | Complete app usable end-to-end manually | start→stop→list→detail→rename→merge→re-export on device |
| **M5** | WP-21 | Automated integration suite green | full `testDebugUnitTest` incl. TC-PIPE-1…9 |
| **M6** | WP-22 | Acceptance verified; signed release APK | AC-1…11 (Doc 2 v1.3.0 §14) executed and recorded; `assembleRelease` |

Between M2 and M3 the system "runs" only under scripted transport (by design — M3 is the first point where the real OpenRouter credential and microphone enter, and both are operator-provided contracts, not agent-producible ones).

## 6. Coverage & Traceability Matrix

**Doc 2 v1.3.0 §1 scope items → WPs:**

| §1 item | WPs |
|---|---|
| 1. App skeleton, Kotlin/Compose, minSdk 30, sideload | WP-01, WP-22 |
| 2. Audio pipeline (capture, VAD, WAV, queues, ASR, backfill) | WP-04, 07, 10, 11, 12, 14, 16 (+20, 21 tests) |
| 3. Two-stage LLM pipeline, co-equal analysis | WP-06, 10, 11, 12 (+20, 21) |
| 4. Session lifecycle UX | WP-17, 18, 19 |
| 5. Data: Room, export, retention | WP-02, 03, 05, 08, 13 |
| 6. Debug/observability facility | WP-08 (log/metrics), WP-18 (status panel) |
| 7. Test suite + acceptance | WP-02…13 (distributed unit), WP-20, 21 (integration), WP-22 (acceptance) |

**Doc 2 v1.3.0 §8 modules → WPs:**

| Module | WP |
|---|---|
| §8.0 Types (incl. `SettingsSnapshot`) | WP-02 |
| §8.1 CompanionApp | WP-01 (stub) → WP-15 (final) |
| §8.2 AudioCaptureEngine + Chunker | WP-04 (Chunker), WP-14 (engine) |
| §8.3 WavEncoder | WP-04 |
| §8.4 AudioRetentionStore | WP-08 |
| §8.5 ChatTransport / OpenRouterGateway | WP-07 |
| §8.6 AsrClient | WP-10 |
| §8.7 Prompts | WP-06 |
| §8.8 StatementProcessor | WP-10 |
| §8.9 PipelineOrchestrator | WP-11 + WP-12 |
| §8.10 SessionService | WP-16 |
| §8.11 MarkdownExporter | WP-13 |
| §8.12 AnalysisEngine | WP-10 |
| §8.13 ConnectivityMonitor / PipelineMetrics / SessionLog (incl. `deleteForSession`) | WP-08 |
| §8.14 UI layer — MainActivity + AppNav (host/nav) | WP-17 |
| §8.14 UI layer — Start / Settings | WP-17 |
| §8.14 UI layer — Live + StatusPanel | WP-18 |
| §8.14 UI layer — List / Detail | WP-19 |

**Additional spec surfaces:** §4.1 settings surface → WP-05 (produced) + WP-17 (consumed); §5.2 DAOs → WP-02; §5.3 repository → WP-03; §6 DTOs → WP-06/07/10; §7 auth/permissions → WP-05 (key), WP-01/16/17 (permissions); §12 tests → distributed + WP-20/21; §13 build/deploy → WP-01/22; §14 AC-1…11 → WP-22 (AC-11 seeded in WP-05).

**Union check:** every Doc 2 v1.3.0 §1 item and every §8 module (including the v1.1.0 additions: `SettingsSnapshot`, `SessionLog.deleteForSession`, MainActivity/AppNav) maps to ≥1 WP; the union of all WPs equals 100% of spec scope. Beyond-scope content is limited to declared process artifacts (`docs/wp-reports/`, milestone version bumps) — no application capability outside Doc 2 exists in any WP.

## 7. Completion Criteria

The project is complete when all of the following hold — mirroring Doc 2 v1.3.0 §14:

1. All 22 WPs merged with their verification green; full `./gradlew :app:testDebugUnitTest` (unit + gateway + integration) passes on a clean checkout.
2. AC-1…11 of Doc 2 v1.3.0 §14 executed on the target device and recorded (waivers explicit, none silent).
3. `./gradlew :app:assembleRelease` produces a signed APK that installs and runs the M3/M4 smoke procedures on the A50-class floor device.
4. Spec-of-record integrity: Docs 1–3 unchanged except through versioned revisions; every deviation discovered during implementation exists as a `[CONFLICT]`/`[ASSUMPTION]` entry in a WP report with its resolution. Any future revision of Doc 1 or Doc 2 triggers a re-audit and re-issue of this roadmap before further WPs execute.
5. Caller existence at runtime verified: no interface, module, or type ships without a caller (enforced per-WP in the DoD, audited in WP-22 via the M3/M4 smokes exercising **every seam S1–S30** of Doc 2 v1.3.0 §15.1).

---
