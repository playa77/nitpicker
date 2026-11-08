# Authorized ASR model update — 2026-11-08

The operator requested retaining Microsoft, removing the OpenAI transcription option, and adding exactly `mistralai/voxtral-mini-transcribe-3-0` after updating the account guardrail. This supersedes the Whisper availability limitation recorded in earlier WP reports; those reports remain historical verification records.

## Changes

- MAI (`microsoft/mai-transcribe-2`) is the default. Voxtral Mini Transcribe 3 (`mistralai/voxtral-mini-transcribe-3-0`) is the second selectable model. There is no OpenAI runtime ASR option.
- Model aliases are retained as requested; the operator's guardrail permits the dated snapshots `microsoft/mai-transcribe-2-20260903`, `mistralai/voxtral-mini-transcribe-3-0-20261020`, and `google/gemini-3.1-flash-lite-20260507`, and providers Azure, Mistral and Google AI Studio. Successful live dispatches verify access. No guardrail/provider settings were changed by the implementation.
- Existing unsupported saved settings resolve to MAI. Recovery rewrites unsupported model selections only on ACTIVE/STOPPING sessions, before workers dispatch. Supported Voxtral selections and historical completed/failed session metadata are preserved. Focused regression tests cover the selection list, saved values, removed-model recovery, retained Voxtral selection, and completed history.
- Added a locally synthesized German fixture alongside the existing synthetic English speech. Both are short single-speaker recordings containing no real meeting data.
- Revised the design to v1.2.0, technical specification to v1.3.0, and re-audited/rebound the roadmap at v1.3.0 for this operator-authorized model change. Endpoint, pipeline, schema, work-package boundaries and acceptance requirements are otherwise unchanged. Multipart WAV transport is shared by both models; no chat-audio fallback was added.

## Live verification

All nine live scenarios passed across the initial run and a focused retry: four model/language gateway cases, four model/language ASR→segmentation→analysis production-client cases, and Gemini's segmentation/analysis contract case. The initial MAI English gateway call received HTTP 429; the test deliberately uses a one-attempt gateway. Its isolated rerun passed. German/English Voxtral gateway and both complete production-client chains passed on their first run.

Observed single-request transcription durations from the initial run: Voxtral German 2058 ms, Voxtral English 784 ms, MAI German 867 ms. These use different short synthetic fixtures, include network/request time, and are smoke measurements rather than a comparative benchmark or latency acceptance result.

`OPENROUTER_API_KEY` was read only by opt-in test processes, never written to app settings, source or Gradle properties. Device/multi-speaker acceptance remains outstanding.


## Final local verification

`./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain` → BUILD SUCCESSFUL. JUnit: 78 discovered, 69 passed, zero failures/errors, nine opt-in live scenarios skipped in the offline run. All nine TC-PIPE integration cases remain green. The separate live runs cover all nine live scenarios successfully, with the single initial MAI 429 and focused passing rerun documented above. Lint has zero errors (14 existing warnings). `git diff --check` passes. No Room schema migration or dependency version change is required.
