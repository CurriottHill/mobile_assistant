# Marvin Mobile Assistant

Marvin is an Android AI assistant prototype that can answer questions, listen to voice input, speak responses, and operate apps through Android accessibility APIs. I built it as a portfolio project to explore what a phone-native assistant can do when an LLM has structured tools, screen context, and guarded automation primitives.

The app is intentionally powerful: it can inspect the foreground UI, draw an overlay above other apps, and dispatch taps, swipes, text entry, and navigation. Run it on a test device first and review the permissions before using it with personal accounts.

## What It Demonstrates

- Floating assistant overlay launched from the app or Android assistant shortcut.
- Accessibility-backed screen reading, UI targeting, scrolling, tapping, typing, and app navigation.
- Multi-step agent loop with tool schemas, execution history, cutoff handling, and provider fallback paths.
- Voice input through AssemblyAI transcription and streaming text-to-speech through Deepgram.
- Integrations for Spotify, Google account services, calls, SMS, WhatsApp, calendar, maps, weather, notifications, memory, and device utilities.
- Local evaluation harness with task catalogs, run recording, scoring helpers, and regression tests for tool behavior.
- Optional Firebase auth plus optional hosted account/credit backend, both disabled unless configured locally.

## Architecture

The app has two user-facing layers:

- `AssistantOverlayController` owns the overlay UI, microphone capture, transcription, TTS playback, and lifecycle coordination.
- `AgentManager` owns the LLM loop, prompt state, model/provider fallbacks, tool-call parsing, execution telemetry, and final response handling.

Device actions are split into focused services such as `SpotifyService`, `MapsToolService`, `CallToolService`, `SmsToolService`, `CalendarToolService`, `MemoryToolService`, and `GoogleAccountService`. Shared low-level automation lives in classes such as `ScreenReader`, `ScreenGestureDispatcher`, `ScreenPageScrollDispatcher`, and `AssistantAccessibilityService`.

More detail is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tech Stack

- Kotlin and Android Views
- Android Accessibility Service and Notification Listener Service
- Gradle Kotlin DSL
- OkHttp and coroutines
- Firebase Auth, optional
- AssemblyAI, Deepgram, OpenRouter, Anthropic, OpenAI, Google, Spotify APIs, all configured locally

## Setup

### Prerequisites

- Android Studio with the Android SDK installed
- Android 7.0+ device or emulator, API 24+
- A local `local.properties` file based on `local.properties.example`
- At least one LLM provider key, depending on the provider path you want to exercise

### Local Configuration

Copy the example config:

```bash
cp local.properties.example local.properties
```

Then fill in only the services you want to use:

```properties
sdk.dir=/path/to/your/android/sdk

OPENROUTER_API_KEY=your_openrouter_api_key
ANTHROPIC_API_KEY=your_anthropic_api_key
OPENAI_API_KEY=your_openai_api_key
ASSEMBLY_AI_API_KEY=your_assemblyai_api_key
DEEPGRAM_API_KEY=your_deepgram_api_key
SPOTIFY_CLIENT_ID=your_spotify_client_id
SPOTIFY_REDIRECT_URI=mobile_assistant://spotify-auth-callback
MAPS_API_KEY=your_maps_api_key
```

`local.properties` is gitignored. Do not commit API keys, Firebase exports, keystores, screenshots from personal devices, or generated evaluation archives.

### Optional Firebase Auth

Firebase is only needed for account sign-in flows. For local builds you can either:

- Put Firebase values in `local.properties`, or
- Copy `app/google-services.example.json` to `app/google-services.json` and replace the placeholders.

`app/google-services.json` is gitignored because it is environment-specific.

### Optional Hosted Backend

The hosted account/credit backend is disabled by default. Set these only if you have your own compatible backend:

```properties
MARVIN_API_BASE_URL=https://your-backend.example.com
MARVIN_BILLING_URL=https://your-billing-page.example.com
```

Blank values are valid for portfolio/demo builds. The app will show a clear "not configured" message instead of calling a private deployment.

## Build And Test

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

Install the debug APK from `app/build/outputs/apk/debug/`, or use Android Studio.

## Runtime Permissions

Marvin asks for permissions according to the feature being used:

| Permission or service | Purpose |
| --- | --- |
| Accessibility Service | Read on-screen UI, draw overlay, tap, scroll, type, and navigate |
| Notification Listener | Read notification summaries when requested |
| `RECORD_AUDIO` | Voice input |
| `INTERNET` | API calls |
| `READ_CONTACTS` | Resolve contact names for calls and messages |
| `CALL_PHONE` | Initiate calls |
| `SEND_SMS` | Send SMS messages |
| `READ_CALENDAR` / `WRITE_CALENDAR` | Read and create calendar events |
| Location permissions | Estimate routes and travel time |
| `SET_ALARM` | Create timers and alarms |

## Safety Boundaries

The agent is designed to refuse banking, payment, purchase, card-detail, and money-transfer workflows. Those restrictions are present in the prompt/tooling layer and should be treated as a safety aid, not a formal security boundary. Keep testing on non-critical accounts and review any new tool before enabling it by default.

## Repository Hygiene

This repo is prepared for public development:

- Local secrets and generated outputs are ignored.
- Firebase config has a template instead of a real environment file.
- Hosted backend URLs are opt-in.
- Setup, architecture, security, and contribution notes are documented.

Before publishing an existing branch, also check the git history for previously committed secrets or personal screenshots. `.gitignore` protects future commits, but it does not rewrite history.

See [docs/PUBLISHING_CHECKLIST.md](docs/PUBLISHING_CHECKLIST.md) for the final public-release checklist.

## License

Apache License 2.0. See [LICENSE](LICENSE).
