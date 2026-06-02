# Contributing

This is primarily a portfolio project, but issues and pull requests are welcome.

## Development Setup

1. Install Android Studio and the Android SDK.
2. Copy `local.properties.example` to `local.properties`.
3. Fill in only the provider keys needed for the feature you are testing.
4. Run unit tests before opening a pull request:

```bash
./gradlew :app:testDebugUnitTest
```

## Pull Request Guidelines

- Keep changes focused on one behavior or integration at a time.
- Add or update unit tests for tool routing, parsing, prompt helpers, or math/state-machine changes.
- Do not commit `local.properties`, `app/google-services.json`, keystores, generated APKs, personal screenshots, or eval archives.
- Be careful with accessibility automation changes. Include the device/app scenario you tested.
- Document any new runtime permission or external service in `README.md`.

## Code Style

Follow the style already in the surrounding Kotlin files. Prefer small service classes for new tools and keep provider-specific JSON handling contained to support/test helpers where possible.
