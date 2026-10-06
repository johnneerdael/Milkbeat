# Public plugin API contract

This workspace keeps the TypeScript SDK and JSON schema aligned with Milkbeat's native plugin API. The app's generic plugin runtime, installation, updates, sign-in and download-code support remain in the public app repository.

Plugin API 4 adds optional Widevine audio descriptors (`AudioStream.drm`). The host uses Media3 and
validates license, redirect and provisioning destinations against installed network grants.
License headers stay separate from media headers/caches. Clear audio remains compatible; plugins
requiring this extension must declare API 4 as their minimum. HLS/DRM offline downloads are unsupported.

Audio providers can opt into `roles.audio.batchMatching` and implement `audio.matchBatch` for up to 16 ordered tracks. Each result slot contains candidates or a per-track error. `SONGS`, `ALTERNATE_SONGS` and `VIDEOS` strategies let the host request fallbacks only after rejecting earlier candidates. An optional empty `ENSURE` import can run alongside the searches within one evaluation; its result or error is reported separately. The host keeps QuickJS root ownership serialized, shares its match cache with playback, and checks cancellation before starting another search strategy.

Private playlist import results can report `PREPARING`, `WRITING` and `VERIFYING` progress. A completed import must have confirmed the full ordered occurrence list, including repeated IDs, and the destination's ownership and private visibility. All additions to plugin API v1 have backward-compatible defaults; older audio providers continue through `audio.match`.

```sh
npm ci
npm run check
```

Compiled third-party packages are not kept in git; they download only from Buzzheavier. The publisher verifies each package against its Buzzheavier account listing before registering it, and supplies the publication metadata (`published.json`) and the encrypted download catalog, which the app CI checks against each other.

Third-party plugin packages are distributed separately from Milkbeat. Milkbeat releases contain the app APKs and checksums; the app's README lists optional third-party downloader codes.
