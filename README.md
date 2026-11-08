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

Live checks always execute rather than using Gradle's up-to-date/build-cache results. They exercise multipart audio transcriptions for both ASR models and chat completions for Gemini; they send only a small synthetic speech fixture and synthetic statements. Never store the test key in source or Gradle properties. Whisper currently requires an account provider-guardrail change before its live check can pass; MAI and Gemini passed on 2026-11-08. This does not change the application's configured/default model.

For a signed release, create an untracked `keystore.properties` containing `storeFile`, `storePassword`, `keyAlias`, and `keyPassword`, then run:

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Never commit signing credentials or keystore files. On first launch, grant the requested permissions and enter the OpenRouter API key in Settings.

## Project documents

- [Design document](docs/design_doc.md) — product goals, constraints, architecture, and decisions (v1.1.0, Approved).
- [Technical specification](docs/technical_specification.md) — implementation structure, interfaces, testing, and release workflow (v1.2.0, Approved).
- [Implementation roadmap](docs/roadmap.md) — dependency-ordered work packages and acceptance milestones (v1.2.0, Approved).

## License

Nitpicker is released under the MIT License. See [LICENSE](LICENSE).
