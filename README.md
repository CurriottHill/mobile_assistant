# Mobile Assistant

An Android AI assistant that runs as a floating overlay and can control your phone. Speak or type a request — it answers conversationally or executes tasks on-screen using Android's accessibility APIs.

## Features

**Conversational AI**
- Voice input via OpenAI Whisper transcription
- Text-to-speech responses via Cartesia
- Persistent conversation history across turns

**Phone Automation**
- Read what's on screen and interact with any app
- Tap, scroll, swipe, type, navigate
- Open apps and URLs

**Integrations**
- Spotify — play songs, albums, playlists by name or search query
- Phone calls and SMS — resolves contact names automatically
- WhatsApp messaging
- Timers, alarms, and stopwatch

## How It Works

The assistant runs as an Android Accessibility Service, which lets it draw an overlay on top of other apps without stealing focus. This means it can read the active app's UI tree and interact with it while you're using any other app.

There are two AI layers:
1. **Chat** — handles questions and general requests (Claude Haiku)
2. **Agent** — activated when a phone action is needed; plans and executes multi-step tasks using screen observation + tool calls

## Setup

### Prerequisites
- Android 7.0+ (API 24)
- [Anthropic API key](https://console.anthropic.com/) (Claude)
- [OpenAI API key](https://platform.openai.com/) (Whisper transcription)
- [Cartesia API key](https://cartesia.ai/) (TTS)
- [Spotify app](https://developer.spotify.com/dashboard) credentials (optional)

### Build Configuration

Copy `local.properties.example` to `local.properties` and fill in your keys:

```properties
sdk.dir=/path/to/your/android/sdk

OPENAI_API_KEY=sk-proj-...
CARTESIA_API_KEY=sk_car_...
SPOTIFY_CLIENT_ID=your_spotify_client_id
SPOTIFY_REDIRECT_URI=mobile_assistant://spotify-auth-callback
```

The Anthropic key is entered at runtime in the app (not stored in `local.properties`).

### Runtime Setup

1. Install and launch the app
2. Accept Terms & Privacy
3. Tap **Open Accessibility Settings** and enable **Mobile Assistant**
4. Return to the app and enter your Anthropic API key
5. Optionally connect Spotify

Hold the power button (or set the app as your default assistant) to open the overlay.

## Permissions

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Voice input |
| `INTERNET` | API calls |
| `READ_CONTACTS` | Resolve names for calls/SMS |
| `READ_CALL_LOG` | Call back last caller |
| `CALL_PHONE` | Initiate calls |
| `SEND_SMS` | Send text messages |
| `SET_ALARM` | Timers and alarms |
| Accessibility Service | Overlay and screen control |

## Safety

The agent will not open banking or payment apps (PayPal, Venmo, Zelle, etc.) or perform any action involving real money, purchases, or card details. This is enforced in the system prompt and cannot be overridden by user instructions.

## License

See [LICENSE](LICENSE).
