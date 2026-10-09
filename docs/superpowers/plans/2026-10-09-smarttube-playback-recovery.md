# SmartTube playback recovery implementation plan

> Use the executing-plans skill to implement this plan inline, with regression tests and review checkpoints.

**Goal:** Match current SmartTube's YouTube contract, keep music audio continuous when switching video/visualizations, preserve ordered provider fallback, and remove lyrics.

**Architecture:** Keep Media3 as the sole player. Prepare optional picture metadata with the audio resolution; construct one DASH, HLS or SABR presentation and toggle its video track instead of replacing the media item. Keep YouTube Music independent while YouTube Video becomes the preferred music playback provider.

**Tech stack:** Kotlin, plugin API/SDK, Media3 1.11.0, existing NewPipe format adapters, TypeScript private providers.

**Spec:** The user's active goal and explicit requirements: regular YouTube matching, direct adaptive playback first, SABR fallback, audio order YouTube Video → SoundCloud → Beatport, no lyrics, visible matching progress, device 192.168.50.80 only, latest SmartTube contract checks without a frozen pin.

## Constraints and review focus

- Preserve local/downloaded media, queue identity, provider/account grants and cancellation.
- Optional picture preparation must never exclude an audio-only fallback provider.
- A hidden video track must not fetch picture bytes or rebuild the audio decoder.
- API additions must remain optional for existing providers and have generated SDK/schema coverage.
- Source parity does not establish device playback: verify real streaming and repeated switches on the TV.
- Keep final-head Codex review, required CI, docs assessment and main merge as completion gates.

## Tasks

- [x] Private provider: direct-first extraction and latest-source contract CI. Pushed b692b41; not merged.
- [ ] Add regressions proving switches do not replace items/seek, and single-video DASH metadata remains valid.
- [ ] Add optional `prepareVideo` request and chosen `audioFormat` response metadata to API 7; generate schema/SDK and preserve old-provider defaults.
- [ ] In `PluginAudio`/`DownloadUtil`, prepare optional A/V metadata independently of rendering and hard picture requirements; validate descriptors and retain audio-only provider order.
- [ ] Reuse `PluginVideoStreams`, `AdaptiveDashManifest` and `MediaSourceBuilder` in `MusicMediaSourceFactory`; let Media3 load only selected tracks.
- [ ] Remove source replacement from `MusicPlaybackVideo`; test preserved audio selection, item identity and position.
- [ ] Add bounded playback fallback and tests for failed YouTube resolutions/media while the original SoundCloud recording remains available.
- [ ] Publish foreground-only matching/loading status with cache/prefetch/cancellation isolation tests.
- [ ] Remove lyrics fetchers, consumers and settings; preserve ordinary video captions and run affected UI/build checks.
- [ ] Update private YouTube Video to return prepared metadata; synchronize SDK, validate latest upstream and sign a new immutable provider version.
- [ ] Evaluate README, guide/Pages, API docs and AGENTS; run focused/full validation, native acceptance, review, CI and merge both repositories.
