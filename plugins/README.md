# Public plugin API contract

This workspace keeps the TypeScript SDK and JSON schema aligned with Milkbeat's native plugin API. The app's generic plugin runtime, installation, updates, sign-in and download-code support remain in the public app repository.

Plugin API 7 adds optional `ResolveAudioRequest.prepareVideo` and `AudioStream.audioFormat` metadata. Picture preparation remains optional; providers can return audio only. A chosen audio format retains its exact rendition ID, URL and DASH byte ranges. The host prepares one Media3 presentation and toggles video track selection without replacing the audio source. API 6 and older provider responses remain valid; older video providers receive their existing picture request when preparing a presentation. Separate progressive picture URLs without a prepared DASH presentation remain audio-only in the music player, avoiding hidden video range requests. Native SABR and eligible HLS presentations retain their picture support.

Plugin API 5 adds generic `deviceCode` sign-in methods and `signIn.begin/poll/confirm/cancel` operations.
The native screen displays a permission-checked HTTPS activation URL, QR code and user code;
polling follows the provider’s cadence only while the screen is resumed. Pending sessions are
cancelled on navigation or pause, and expired/denied results offer retry. `webLogin` remains supported.
`crypto.randomBytes({length})` returns 1–256 platform-generated random bytes as hex; optional
`env.get` OS/model fields let plugins describe this installation without captured device IDs.
Plugins needing these additions must declare API 5 as their minimum.

Plugin API 4 adds optional Widevine audio descriptors (`AudioStream.drm`). The host uses Media3 and
validates license, redirect and provisioning destinations against installed network grants.
License headers stay separate from media headers/caches. Media and license requests share refresh
state so initial key acquisition and later renewals use current credentials. Clear audio remains
compatible. Plugins requiring this extension must declare API 4 as their minimum. HLS/DRM offline
downloads are unsupported.

Audio providers can opt into `roles.audio.batchMatching` and implement `audio.matchBatch` for up to 16 ordered tracks. Each result slot contains candidates or a per-track error. `SONGS`, `ALTERNATE_SONGS` and `VIDEOS` strategies let the host request fallbacks only after rejecting earlier candidates. An optional empty `ENSURE` import can run alongside the searches within one evaluation; its result or error is reported separately. The host keeps QuickJS root ownership serialized, shares its match cache with playback, and checks cancellation before starting another search strategy.

Private playlist import results can report `PREPARING`, `WRITING` and `VERIFYING` progress. A completed import must have confirmed the full ordered occurrence list, including repeated IDs, and the destination's ownership and private visibility. All additions to plugin API v1 have backward-compatible defaults; older audio providers continue through `audio.match`.

```sh
npm ci
npm run check
```

Compiled third-party packages are not kept in git; they download only from Buzzheavier. The publisher verifies each package against its Buzzheavier account listing before registering it, and supplies the publication metadata (`published.json`) and the encrypted download catalog, which the app CI checks against each other.

Third-party plugin packages are distributed separately from Milkbeat. Milkbeat releases contain the app APKs and checksums; the app's README lists optional third-party downloader codes.

Stable publication is `published.json` with `app/src/main/assets/plugin-download-catalog.json`;
its native acceptance fixture is `app/src/androidTest/assets/published-plugins.json`. Prerelease
versions are rejected by the stable validator. `published-preview.json` records the signed
packages pinned by `app/src/nightly/assets/plugin-preview-download-catalog.json` and is evaluated
with the explicit preview channel. Original archive hashes and URLs remain immutable when a
rebuild changes only its signature envelope. Actual package content changes require a new versionCode.
