# SmartTube TV Provider Implementation Plan

> For agentic workers: use the executing-plans skill for inline execution, with test-first tasks and a final independent review. Preserve the currently agreed scope in the design.

**Goal:** Deliver a separate YouTube video plugin using SmartTube playback, TV/web tokens, SABR and QR sign-in, with its complete Music section and highest-resolution playback wallpaper, while retaining YouTube Music.

**Architecture:** Provider-specific discovery/auth/extraction stays in the private plugin. Generic SABR transport, additive stream metadata and artwork rendering integrate with the existing Media3 player and plugin host. Port the pinned local SmartTube implementation with provenance, rather than substitute a smaller direct-URL-only implementation.

**Tech stack:** Existing Kotlin/Compose/Media3 1.11.0 host, typed JavaScript plugin SDK, SmartTube's local SABR Java/protobuf implementation adapted to Media3, existing HTTP/browser/crypto storage and device-code UI.

**Spec:** `docs/superpowers/specs/2026-10-08-smarttube-tv-provider-design.md`.

## Global constraints

- Public worktree `.worktrees/smarttube-tv-provider`, branch `feat/smarttube-tv-provider`, base ce3d3e318b9e19a338a74ad498bf3688acd8fbdc. Private same task worktree/branch, base f954b161f096039e4348cdbd0d6afd18af6ee9b8.
- New provider ID `nl.neerdael.youtube-video`; existing `nl.neerdael.youtube-music` source, identity, catalog codes and sessions remain intact.
- Local SmartTube bf9cd3142 and MediaServiceCore de7f55fbcd925ff1875e6663eb307f3611bfc67f are the source references. No web research.
- Music section is mandatory; use regular YouTube TV feeds and SmartTube continuations, not WEB_REMIX home or unrelated sections.
- One Media3 engine/media-session owner. No global DI migration, polling, new wake locks or silent downgrade of the 4K acceptance target.
- Audio-only and video share the accepted extraction/recording; original metadata-provider IDs remain unchanged.
- Never log token values, codes, signed URL queries or authorization. Keep live browser handles process-local.
- Keep current API/wire fields compatible, regenerate SDK/schema, retain original publisher key, update docs and obtain current-head review/CI before merge.

## Review focus

1. An expired token, missing browser or concurrent reset cannot reuse another visitor/account's attestation or crash a 32-bit device.
2. A response with SABR but no direct URLs is playable; reload-response/CDN/token failures do not become infinite retries or a generic IP accusation.
3. Music rows, Liked Music, nested Show More and page continuations keep ordering/identity and never duplicate a fetch or change provider during account switching.
4. Audio-only performs no video media requests; switching/seek/recovery preserves the accepted recording and position, and hidden video stops work.
5. Playback wallpaper selects the best available thumbnail with fallback, independent of metadata provider, without rewriting catalog identity.

## Task 1: Native host runtime safety

Files: `app/src/main/java/io/github/aedev/flow/plugin/runtime/PluginRuntime.kt`, existing runtime tests discovered by imports, and a native code-load device regression.

- [ ] Pin the synchronous QuickJS.compile call path and async binding dispatcher using the resolved 1.0.15 artifact; record worker thread and native stack evidence.
- [ ] Write/verify a failing thread-affinity/native large-player-code regression through actual code.load, including overlapping calls and cancellation.
- [ ] Confine compilation and execution to the context owner dispatcher without deadlocking the async host bridge; preserve limits, idle/hold behavior and cancellation.
- [ ] Run affected unit/native regressions, flavored builds and lint; commit and push a focused checkpoint.

## Task 2: Stream contract and SABR library

Files: new `plugin-api/src/main/kotlin/nl/neerdael/milkbeat/plugin/ServerAbr.kt`, additive `Streams.kt`, generated public/private SDK/schema, new `media3-sabr/` module with pinned upstream protocol/parser/source adaptation and notices; root Gradle wiring/version catalog only for required non-overlapping tooling/dependencies.

- [ ] Add failing wire compatibility tests for legacy progressive/HLS/DASH inputs, optional playback artwork, SABR configuration/format discriminators, opaque reload context and cancellation/error contracts.
- [ ] Define additive server-ABR presentation data carrying endpoint, ustreamer config, client info, token/cookie data, duration/live metadata, formats and format IDs (itag, lastModified and xTags). Add artwork to audio playback. Use an incremented host API floor only for the new provider.
- [ ] Generate maintained protobuf classes from SmartTube definitions. Port upstream SABR behavior to Media3 source/period/chunk/extractor APIs with preserved attribution; reuse Media3 primitives and parser adapters.
- [ ] Verify actual audio-only selections, format identity, segment ordering, seek, caption separation, redirects/CDN selection, protection status, ReloadPlayerResponse and malformed/truncated packets with real parser/source fixtures.
- [ ] Verify API/schema parity and compile both app flavors; commit/push the tested checkpoint.

## Task 3: Separate SmartTube provider

Private files: new `youtube-video/` package/manifest/src/test/assets; workspace registration, publisher/test provider inventory and source pin; existing YouTube Music files are not implementation targets.

- [ ] Characterize SmartTube AppService/AppClient, QueryBuilder, VideoInfoService/FormatInfoWrapper and TV/web token providers with exact request/response fixtures and provenance.
- [ ] Implement matching regular-YouTube client headers, visitor/living-room binding, client-specific timestamp/signature solving, TV/web token generation and lifetime. Preserve the meaningful upstream fallback/recovery behavior, including SABR responses.
- [ ] Expose one extraction used by both audio.resolve and video.resolve; select audio-only or combined supported renditions, return highest available thumbnail artwork and stable rendition/cache identity.
- [ ] Port SmartTube device-code/QR endpoints and refresh/account behavior through existing device-code host operations; test pending, slowdown, expiry, cancellation and guest independence.
- [ ] Test guest/signed-in fixtures, no Music endpoint dependency, token/session failure/recreation, all stream types and 4K format selection. Install/check isolated dependencies in this worktree, run full provider tests/typechecks, and commit/push milestones.

## Task 4: Mandatory SmartTube Music section

Private files: `youtube-video/src/music/` catalog, row/item mapping, continuations and search/entity/radio adapters; fixtures/tests from SmartTube TV Music feed behavior.

- [ ] Pin `getMusicObserve` ordering: Liked Music when available, then complete FEtopics_music rows; verify actual TV browse request and signed-in/guest handling.
- [ ] Implement rows/items preserving IDs, titles, thumbnails, navigation and continuation tokens; map videos into music-playable descriptors without inventing album/artist credits.
- [ ] Support row Show More and page continuations using existing Milkbeat catalog surfaces; preserve fetch cardinality and account/provider scope. Implement required track/entity/search/radio paths used by these feeds.
- [ ] Verify complete feeds against local fixtures and operational guest browse; test empty/auth-only rows, nested continuations, cancellation and account switches.
- [ ] Run catalog tests and device UI checks; commit/push the checkpoint.

## Task 5: Host playback and wallpaper integration

Files: music/video plugin media-source factories, SABR presentation adapter/factory, accepted-resolution artwork policy/Flow and TV Now Playing consumer; relevant existing tests and native scenarios.

- [ ] Add failing integration tests for SABR-only plugin responses, transport reload errors, original metadata identity and thumbnail selection independent of metadata provider.
- [ ] Route SABR into the existing Media3 player using the new module. Bind fresh extraction/reload callbacks to the same accepted source/account and cancel obsolete work.
- [ ] Use accepted playback artwork in Wallpaper view with best-thumbnail and original-artwork fallbacks, existing Coil cache/lifecycle and unchanged track metadata.
- [ ] Verify real audio-only vs video requests, 2160p decoded dimensions, position/source continuity, hidden/background behavior, unsupported picture recovery and memory/thread cleanup.
- [ ] Validate full flavored app/API suites and lint; commit/push.

## Task 6: End-to-end delivery

- [ ] Evaluate/update README, Pages guide and navigation, plugin architecture and AGENTS; document both providers, Music feeds, QR setup, guest access, SABR and wallpaper. Validate strict guide build and publication tables/schema.
- [ ] Package/sign the new provider with original author, preserve immutable signed bytes, register a separate download code, and verify host/API compatibility and remote/local hashes.
- [ ] Build the paired app and run guest sustained 2160p playback on the non-root TV at the same IP, including seek/view/background recovery. Require actual user QR authorization before claiming signed-in native verification.
- [ ] Capture Music section and highest-resolution wallpaper screenshots and sanitized runtime evidence. Compare the same set with working SmartTube/TizenTube without disrupting unrelated playback.
- [ ] Complete independent code review, ready public/private PRs, exact-current-head Codex review and all required CI; process every finding, merge according to repository gates and verify paired publication/installation.
- [ ] Audit each spec requirement against source, native/runtime results, artifacts and merged PR state before completing the goal.
