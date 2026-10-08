# SmartTube TV provider

## Outcome and agreed scope

Create a separate YouTube video provider, `nl.neerdael.youtube-video`, alongside `nl.neerdael.youtube-music`. Implement SmartTube's playback, living-room and web attestation, device-code/QR sign-in, and its Music section end to end. The Music section is mandatory: preserve its feeds, rows, item order, continuation behavior and signed-in Liked Music feed, rendered through Milkbeat's existing TV catalog surfaces. Do not recreate unrelated SmartTube browse sections. Do not remove, replace or alter the existing YouTube Music provider. Local SmartTube source is the reference; no web research.

Both audio-only and video playback consume the same regular-YouTube video-id extraction. Audio-only selects audio and makes no picture media requests. Video exposes all available supported renditions, including 2160p. Switching views retains the original catalog identity, accepted recording and position. Return the highest available video thumbnail with audio playback so Artwork view can use it even when SoundCloud/Spotify provides metadata. Keep catalog title, performer, source IDs and queue identity unchanged.

## Authoritative evidence

- Milkbeat base: ce3d3e318b9e19a338a74ad498bf3688acd8fbdc. Private provider base: f954b161f096039e4348cdbd0d6afd18af6ee9b8.
- SmartTube: bf9cd3142; MediaServiceCore: de7f55fbcd925ff1875e6663eb307f3611bfc67f; SharedModules: 13f5687dd6757b02fbcdf14c5403d0339e377db5.
- Non-rooted TCL Smart TV Pro API 34, Preview 0.9.47-nightly: 04:26:11 media HTTP 403; 04:26:14 PO-token mint reports missing browser session; 04:26:18 native QuickJS SIGSEGV; crashdump reports likely stack overflow on DefaultDispatch. TizenTube Cobalt plays the same set on the same TV/IP according to the maintainer. These observations do not prove a general IP block.
- Existing audio.resolve asks player with site=music even for video-matched tracks and optional pictures. Separate video.resolve asks regular www and explicitly excludes SABR.
- SmartTube Music uses BrowseService2.getMusic → FEtopics_music through www.youtube.com TV browse, preceded by Liked Music when available. Optional new-album helpers elsewhere use Remix; they are not the source for this section.
- SmartTube's TvPoTokenProvider binds a session token and visitor data to livingRoomPoTokenId from the same /tv page and passes tvAppInfo.livingRoomPoTokenId. Web tokens have separate bindings/lifecycle.
- SmartTube ErrorFixerController and VideoInfoService reset token/cache or advance clients on actual stream failures, then reload. Its SABR transport also handles CDN selection and ReloadPlayerResponse.
- Resolved Media3 1.11.0 artifacts contain no SABR implementation; current plugin Streams.kt exposes progressive formats/HLS/DASH and no SABR config. Returning a serverAbrStreamingUrl as a progressive URL is not sufficient.
- Existing static artwork reads the original track.highResThumbnailUrl; accepted audio-source artwork is not used. The current component itself applies no blur.

## Integration architecture

1. Keep all YouTube endpoints, discovery, client profiles, auth and extraction in the new private plugin. Port SmartTube source behavior with provenance and license notices, preserving its request/header/session coupling. Music home is the SmartTube TV Music section, not WEB_REMIX home. Reuse Milkbeat catalog rows, continuations, entity pages and TV navigation.
2. Extend the generic plugin stream contract additively for SABR and playback artwork. Preserve existing fields/defaults for all current providers. The new provider requires the resulting host API; older providers remain usable. Generate SDK/schema from host definitions and validate public/private parity.
3. Adapt SmartTube's SABR source/parser and maintained protobuf definitions to Milkbeat's existing Media3 engine in a separate library module. Preserve protocol behavior, audio/video track selections, seeking, reload context and token/CDN error handling. Keep a single existing player and media-session owner. Do not import SmartTube's application UI, player singleton or legacy ExoPlayer engine.
4. Map backend format metadata into one source presentation. Store sensitive tokens only through existing plugin storage; never log tokens, authorization headers, user/device codes or signed URL queries. Keep attestation page handles ephemeral and validate ownership/lifetime; do not restore process-local browser handles from durable storage as live sessions.
5. Expose SmartTube device-code authentication through the existing generic TV pairing flow. Preserve pending/slow-down/expiry/cancel semantics, encrypted credential storage, account selection and refresh; display QR locally using existing host support. Anonymous playback must remain independent of sign-in.
6. Carry highest-resolution available playback artwork through the accepted resolution. Prefer that picture in Now Playing wallpaper while preserving original catalog metadata; fallback to lower available thumbnail and then original artwork on failure. Honor Coil caching and lifecycle; no polling, artist-image service or unrelated UI redesign.
7. Reproduce and fix the shared native scripting failure where required by this provider. QuickJS.compile is synchronous native work; code.load currently reaches it from an async host callback. Verify thread/stack affinity with the resolved dependency and native regression before asserting a cause or fix. Preserve timeout, concurrency, memory and lifecycle contracts.

## Acceptance evidence

- Offline fixtures pin SmartTube TV Music rows, Liked Music, row and page continuations, item identities, exact regular-YouTube endpoints, and matching. No empty placeholder section or substitute YouTube Music feed counts as completion.
- Guest and signed-in player fixtures verify same TV session visitor/token/id, exact client headers and timestamp variant; token loss, expired token, refused URL, URL expiration, immediate and delayed 403, CDN failover and ReloadPlayerResponse remain distinct.
- QR/device flow tests cover successful authorization, pending, slowdown, expiry, cancellation, token refresh and signed-out state. Actual user scan/login is required to claim native signed-in success.
- SABR tests use upstream protocol fixtures/definitions and real parser/media-source behavior: audio-only has no video requests, selected 2160p is actual decoded video, seek/reload retains position, missing/unsupported video returns audio, captions do not become SABR media selections, malformed/truncated responses and cancellation release work.
- Native scripting regression runs on 32-bit hardware/emulator with actual large player code through code.load; no native crash and no corruption under overlapping calls, idle close or cancellation. JVM mocks alone are not proof.
- Actual guest 2160p playback on the non-root TV over the same network must continue beyond the observed rapid failure and known minute-long cutoffs; verify rendered dimensions, continuous position/audio, seek, view switches, background/hidden behavior and error logs. Format availability alone is not playback proof.
- Native screenshots verify the Music feeds and sharp artwork while using SoundCloud metadata. Verify highest image dimensions/selected URL and fallback behavior rather than inferring quality from a function name.
- Local flavored builds, tests, lint, SDK parity, published catalog validation and strict guide build pass. Current-head Codex review, resolved findings and reviewed CI gate the public PR; private CI and source/signing checks gate the provider PR. Preserve original publisher identity and immutable signed bytes.
- Document setup, provider choice, QR login, guest behavior, Music feeds, wallpaper and compatibility in README, Pages guide and AGENTS. Carry work through reviewed merged PRs and verified paired installation/publication, preserving unrelated branches and stable plugin assignments.

## Boundaries

No replacement of the existing YouTube Music provider, no migration of its cookies/accounts, no unrelated SmartTube browse sections, no second player/media session, no global DI rewrite, no guessed client impersonation constants or manual app-version edits. Operational endpoint tests are allowed; external internet reference research is excluded. Artwork support belongs to playback and the new provider; third-party artist-image APIs are outside this goal.
