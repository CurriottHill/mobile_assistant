# Security Policy

## Supported Versions

This project is a portfolio/demo Android app. Security fixes target the default branch.

## Reporting A Vulnerability

Please open a private report or contact the maintainer directly if you find:

- A committed secret or credential.
- A way for the agent to bypass payment, purchase, banking, or money-transfer restrictions.
- Unsafe handling of accessibility data, notifications, contacts, location, SMS, calls, or calendar data.
- A dependency or build configuration issue that could expose user data.

Avoid posting exploit details publicly until there is a fix or mitigation.

## Local Secret Handling

API keys belong in `local.properties` or another local secret store, never in tracked source files. Firebase exports, keystores, generated APKs, personal screenshots, and eval archives should also stay out of git.
