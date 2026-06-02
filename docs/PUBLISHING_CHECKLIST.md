# Public Publishing Checklist

Use this before pushing the portfolio repo to a public remote.

## Working Tree

- Confirm `local.properties` is ignored and not staged.
- Confirm `app/google-services.json` is ignored and not staged.
- Remove tracked IDE/build/eval artifacts from the public branch if they are not intentionally curated:

```bash
git rm --cached -r .idea evals_export
git rm --cached evals.tar app/release/output-metadata.json
```

The files can remain on your machine after `git rm --cached`; the command only removes them from the git index.

## Secrets

- Search current source for API keys, private URLs, keystores, tokens, and personal screenshots.
- Rotate any key that was ever committed to a public remote.
- If a real secret was committed in history, remove it with a history-rewrite tool before publishing.

## Portfolio Polish

- Add screenshots or a short demo GIF only after checking that no contact names, messages, location data, or account details are visible.
- Keep the README focused on what the app demonstrates and how another developer can run it locally.
- Run:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```
