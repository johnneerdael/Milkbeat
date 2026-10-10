# Public plugin API contract

This workspace keeps the TypeScript SDK and JSON schema aligned with Milkbeat's native plugin API. The app's generic plugin runtime, installation, updates, sign-in and download-code support remain in the public app repository.

Plugin API 10 adds optional `AudioStream.rangeRequestBytes`: the most bytes one progressive request may ask for, for servers that throttle or refuse long ranges. Leave it out and the host fetches the file in one request. Each provider owns this; the host no longer applies one provider's range limit to every stream. Providers targeting API 9 or older keep the former 512 KiB ranges, so an existing provider that relied on them should set the field when it moves to API 10. Values must be positive. The size is a hard maximum for every request of the stream, including a music video's picture and bounded reads the player makes itself.

Plugin API 9 adds optional provider stream encryption (`AudioStream.cipher`) and `MD5` for `crypto.hash`. The only scheme, `BF_CBC_STRIPE`, takes a 16-byte per-recording `keyHex`: Blowfish CBC encrypts every third whole 2048-byte block, each from the IV `0001020304050607`, and leaves other blocks and a trailing partial block clear. The host removes it while reading one progressive audio rendition and keeps the encrypted bytes in its cache. It refuses a cipher combined with HLS, SABR, DRM, a picture or a chosen DASH format, and a refresh must keep the key, rendition and cache key. Encrypted audio plays but cannot be downloaded for offline use. Use `MD5` only where a provider's protocol names it. Plugins returning a cipher must declare API 9 as their minimum.

For API 8 providers, `lifecycle.warmUp` runs on the persistent playback owner with the ordinary call budget and network grants. Preparation can retain player data and token-minter state instead of discarding it with an isolated context. The host starts it in the background on first provider use; the plugin must deduplicate preparation against simultaneous foreground calls and respect account changes. Older providers retain isolated warm-up.

Plugin API 8 adds optional `ReportPlaybackRequest.positionMs` and `playbackSessionId`. Actual recording position is distinct from cumulative listening time. The host reports qualified music listens while playing, at a bounded 30-second cadence, plus seek/pause/final boundaries. A session identity changes on a new listen, including replaying the same recording. Providers targeting older APIs retain finish-only reporting without the new fields. Reporting uses the accepted cached recording and tracking token; it does not resolve a new stream. Providers must avoid crediting seek jumps and keep failed reports retryable.

Plugin API 7 adds optional `ResolveAudioRequest.prepareVideo` and `AudioStream.audioFormat` metadata. Picture preparation remains optional; providers can return audio only. A chosen audio format retains its exact rendition ID, URL and DASH byte ranges. The host prepares one Media3 presentation and toggles video track selection without replacing the audio source. API 6 and older provider responses remain valid; older video providers receive their existing picture request when preparing a presentation. Separate progressive picture URLs without a prepared DASH presentation remain audio-only in the music player, avoiding hidden video range requests. Native SABR and eligible HLS presentations retain their picture support.

Optional picture preparation does not assert that an HLS response contains video. The host confirms a supported video track in the current Media3 presentation before offering Video; cached metadata cannot enable it for audio-only downloads.

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

Each `published.json` row may state `apiMin` (the package manifest's `api.min`) and `format` (its
container format). Both default to 1 when absent, so older rows stay valid. The app neither offers nor
downloads a release whose `apiMin` or `format` exceeds its own `PLUGIN_API_VERSION` or
`PLUGIN_FORMAT_VERSION`. It reports that the update needs a newer Milkbeat instead. The publisher should
write both fields for every release. `verifyPackage(bytes,{apiMin,format})` in
`scripts/package-verification.mjs` returns the package's values and rejects a row that misstates them.
Do not add fields to the encrypted download catalog's entries: builds before this change decode those
entries strictly and would discard the whole live catalog.

Stable publication is `published.json` with `app/src/main/assets/plugin-download-catalog.json`;
its native acceptance fixture is `app/src/androidTest/assets/published-plugins.json`. Prerelease
versions are rejected by the stable validator. `published-preview.json` records the signed
packages pinned by `app/src/nightly/assets/plugin-preview-download-catalog.json` and is evaluated
with the explicit preview channel. Original archive hashes and URLs remain immutable when a
rebuild changes only its signature envelope. Actual package content changes require a new versionCode.
