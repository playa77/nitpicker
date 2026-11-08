# Nitpicker

Nitpicker is a single-user Android app for consented, in-person business meetings. It turns spoken conversation into a live, timestamped protocol and adds quiet, statement-level fact-checking and logical/rhetorical analysis.

## What it does

- Captures meeting audio and transcribes it into German or English, selected at session start.
- Displays discrete statements as they arrive, with best-effort inferred speaker labels that can be renamed or merged after a session.
- Analyzes statements through OpenRouter using `google/gemini-3.1-flash-lite`. Fact-checking against the model's internal knowledge and logical/rhetorical analysis have equal standing; annotations are observations, not verdicts.
- Persists session and pipeline state locally so queued work can resume after connectivity returns or the app restarts.
- Exports each completed session as a plain Markdown file in the user-accessible Documents/Nitpicker folder.
- Provides a temporary, configurable audio-retention facility for validation and debugging: the first 0–10 minutes of upload audio can be kept app-privately, with a 10-session pruning limit. Setting retention to zero disables it.

## Privacy and consent

Use Nitpicker only when meeting participants have given appropriate consent. Audio chunks are sent to OpenRouter for transcription; transcript and statement text are sent to OpenRouter for segmentation and analysis. The app has no backend, accounts, cloud sync, or remote telemetry. The OpenRouter API key is encrypted with Android Keystore. Exported Markdown is intentionally user-accessible and may contain sensitive meeting content. OpenRouter and its upstream model providers have their own data-handling policies.

## Requirements

- Android 11 (API 30) or newer; the design targets a Samsung Galaxy A50-class device or better.
- An OpenRouter API key and network access to start a session. If connectivity drops during a session, audio capture continues and queued processing resumes when the network returns.
- Microphone permission. Android notification permission is requested where required by the OS.

## Build and test

With the Android SDK configured, use the Gradle wrapper from the repository root:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

The ordinary suite uses scripted transport and MockWebServer. Live inference is opt-in and uses a test-runtime `OPENROUTER_API_KEY`:

```bash
NITPICKER_LIVE_OPENROUTER=1 ./gradlew :app:testDebugUnitTest --tests 'com.nitpicker.llm.OpenRouterLiveTest'
```

Live checks always execute rather than using Gradle's up-to-date/build-cache results. The selectable ASR models are `microsoft/mai-transcribe-2` (default) and `mistralai/voxtral-mini-transcribe-3-0`; OpenAI Whisper is removed. Existing unsupported saved selections and resumable session configurations migrate to MAI. Completed historical sessions retain their original model metadata.

The checks send only short synthetic German/English speech and synthetic statements. They exercise each model's multipart transcription and production ASR → Gemini segmentation → analysis clients. Run all live checks with `NITPICKER_LIVE_OPENROUTER=1 ./gradlew :app:testDebugUnitTest --tests '*OpenRouter*LiveTest*'`. Never store the test key in source or Gradle properties. The current guardrail allows Azure, Mistral and Google AI Studio; its dated model snapshots resolve from the configured aliases. Live German/English gateway and production-client checks passed for MAI and Voxtral 3 on 2026-11-08 (one initial MAI HTTP 429 passed on a focused rerun). Gemini segmentation/analysis also passed.

For a signed release, create an untracked `keystore.properties` containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`, then run:

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Never commit signing credentials or keystore files. On first launch, grant the requested permissions and enter the OpenRouter API key in Settings.

## Implementation status

WP-01–WP-21 are implemented, with the nine scripted PCM-to-export integration scenarios in `TCPipeTest` and focused client/worker tests. The current debug milestone is 0.5.0. Run the full suite and build with the commands above; live tests additionally include `com.nitpicker.llm.OpenRouterClientsLiveTest` (select `--tests '*OpenRouter*LiveTest*'` to run all live checks).

Physical-device M3/M4 smoke checks and WP-22 acceptance/signing remain outstanding. No device or emulator was available in the implementation environment; an API key alone cannot validate microphone behavior, two-speaker usability, latency, or MediaStore visibility. See the per-package reports for verification and assumptions, and the [ASR model update report](docs/wp-reports/ASR-model-update-2026-11-08.md) for the current model configuration and bilingual live results. The runtime environment key is used only by opt-in tests and is never installed into app settings or the APK.

## Project documents

- [Design document](docs/design_doc.md) — product goals, constraints, architecture, and decisions (v1.2.0, Approved).
- [Technical specification](docs/technical_specification.md) — implementation structure, interfaces, testing, and release workflow (v1.3.0, Approved).
- [Implementation roadmap](docs/roadmap.md) — dependency-ordered work packages and acceptance milestones (v1.3.0, Approved).

## License

Nitpicker is released under the MIT License. See [LICENSE](LICENSE).
