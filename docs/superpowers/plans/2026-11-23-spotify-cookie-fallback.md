# Spotify Cookie Fallback Implementation Plan

**Goal:** Automatically offer Spotify cookie import from a desktop browser when WebView profiles are unavailable.

**Architecture:** Preserve existing profile-based login on supported devices. Only the known Spotify cookie-only manifest receives a temporary LAN HTTPS form. The TV shows the URL, certificate fingerprint and session pairing code; submission is accepted only for the active session and passed through the existing plugin completion operation.

**Tech Stack:** Kotlin, Ktor server, Bouncy Castle, Android Compose, existing plugin API.

## Global Constraints

- Keep the browser listener time-limited and scoped to the selected LAN address; never log or place credentials in URLs.
- Stop and clear session state on completion, timeout, cancellation and provider changes.
- Do not enable cookie-only import for providers needing page extraction or WebView refresh.
- Build and validate connected changes together before a focused acceptance session.

## Review Focus

- TLS certificate identity and secret confidentiality against interception and forged requests.
- Desktop entry without scanning a QR code, including certificate verification guidance.
- Invalid and expired cookie handling, repeat submission and stale completion.
- Cancellation, timeouts and provider changes while a request is in flight.
- Unsupported WebViews for other providers and unchanged supported login.

## Batch

- [ ] Implement TLS listener, bounded session and cookie submission, with behavioral tests.
- [ ] Connect automatic fallback, display URL and fingerprint, and document the browser steps.
- [ ] Run focused checks, full required local validation, and one planned acceptance session.
- [ ] Obtain final-head Codex review and passing reviewed PR checks before merging.
