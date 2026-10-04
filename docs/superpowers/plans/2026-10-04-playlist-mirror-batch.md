# Playlist Mirror Batch Implementation Plan

> **For agentic workers:** Use the work-execution workflow task by task. Keep app and publisher changes coordinated. Steps use checkbox syntax for tracking.

**Goal:** Reduce time until a Spotify playlist is playable with bounded parallel matching, early empty creation, one ordered duplicate-preserving bulk edit, stricter matching with conditional fallbacks, and percentage progress; open coordinated PRs and build the new plugin versions.

**Architecture:** Preserve the serialized QuickJS root evaluation. Add optional `audio.matchBatch` whose single root runs bounded searches and an optional ENSURE import concurrently. The native matcher applies shared scoring and Room caching; the runner checkpoints ordered results and performs final REPLACE only after source confirmation. Plugin import progress reports writing and verification stages.

**Tech Stack:** Existing Kotlin/coroutines/Room/Compose Material 3 and QuickJS; existing TypeScript plugin SDK and InnerTube clients. No new runtime dependency.

**Spec:** The active user goal plus the measured local spike at `/Users/jneerdael/Scripts/yt-api-tools/milkbeat-poc/README.md`.

## Global Constraints

- Preserve account/package validation, cancellation, runtime ownership, resume cursors, source revisions, destination ownership/privacy, order and repeated occurrences.
- Run at most 16 anonymous searches per root; native batches contain at most 16 distinct uncached tracks. Yield between batches for foreground priority and checkpoint every completed source position.
- Keep old plugin manifests valid through a default-false `AudioRole.batchMatching` capability and sequential fallback.
- Do not revisit Spotify REST/ISRC sources. Playlist-only Pathfinder pagination uses 100 entries; other collection limits remain unchanged.
- Keep user-facing text in default English resources and Material 3 tokens/components. Read official Compose docs before UI edits.
- Keep player ownership and playback setup unchanged. Readiness occurs only after ordered destination confirmation.
- Build Spotify and YouTube Music plugin version increments; do not publish or modify release catalogs as part of these PRs.

## Review Focus

- Repeated source tracks and existing prefix entries retain the exact intended ordered occurrence list.
- Transient per-track failures never become cached catalog misses; successful work survives retry.
- Cancellation while a root owns concurrent host requests retires/drains it before the next root.
- Weak primary candidates trigger only conditional alternate-song/video searches; unrelated performers or recording versions are rejected.
- Percentage progress includes missed tracks, stays below 100 until confirmation, and handles empty/resumed lists.

## Task 1: Batch API, native matcher and schema

**Files:** `plugin-api/.../Streams.kt`, `PluginManifest.kt`, `PluginOperations.kt`, `catalog/PlaylistManagement.kt`; `PluginTrackMatcher.kt` and a sibling batch helper if needed; public and publisher generated SDK/schema; focused contract/matcher tests.

**Interfaces:** Add `AudioMatchStrategy { SONGS, ALTERNATE_SONGS, VIDEOS }`; optional strategy on `MatchAudioRequest`; `MatchAudioBatchRequest(tracks, strategy=SONGS, playlist: PrivatePlaylistImportRequest?=null)`; `AudioMatches.error: PluginError?`; `AudioMatchesBatch(matches: List<AudioMatches>, playlist: PrivatePlaylistImportResult?=null, playlistError: PluginError?=null)`; `AudioRole.batchMatching=false`; operation `audio.matchBatch` / `PluginOperations.matchAudioBatch`. Add import progress `PrivatePlaylistImportProgress(phase, completed, total)` with `PrivatePlaylistImportPhase { PREPARING, WRITING, VERIFYING }`, nullable on the existing result.

Native batch indexing returns ordered candidate outcomes plus optional ENSURE result. Cache/in-flight ownership remains shared with single matching. Check every candidate with the shared scorer; fallback strategies apply only to rejected primary tracks. No transient failure cache. An older plugin uses existing single matching and import paths.

- [x] Add failing contract tests for defaults and ordered batch/error/progress serialization.
- [x] Implement additive contract and generate checked-in SDK/schema from Kotlin.
- [x] Add failing native matcher tests for cache hits, duplicate IDs, shared in-flight calls, fallbacks only on rejected tracks, errors/cancellation, and combined ENSURE handling.
- [x] Implement cache-aware batch indexing and verify focused tests.

## Task 2: Strict candidate policy

**Files:** `TrackMatchScore.kt`, focused pure policy helper if size requires it, `TrackMatchScoreTest.kt`.

**Interfaces:** Preserve `best(track,candidates)` and its `Scored` return. Accept exact ISRCs as existing native evidence. For fuzzy matches, require independently meaningful title and performer agreement; reject conflicting live/acoustic/remix/version qualifiers and materially conflicting durations. Preserve explicit version identity through normalization. Handle Unicode, punctuation-only titles and featured credits without accepting arbitrary same-artist songs.

- [x] Add failing regression tests from observed bad matches, differing versions, tribute/shared-guest performers, duration conflicts and legitimate naming variants.
- [x] Implement focused gates using existing Kotlin/platform tools, keeping API compatibility.
- [x] Verify policy tests and check effect on matcher fixtures.

## Task 3: Provider operations and builds

**Files:** Private publisher `youtube-music/src/music/match.ts` (split batch helper if needed), entry point, manifest/tests, `playlists.ts`, `spotify/src/catalog.ts`, manifests/package metadata and SDK snapshot.

**Interfaces:** Implement Task 1's exact generated contract. `audio.matchBatch` runs one bounded `Promise.all` round and optional ENSURE in the same root, preserves result slots, reports per-track and playlist failures. Strategy SONGS uses today's query; ALTERNATE_SONGS uses a changed metadata query; VIDEOS searches only requested fallback tracks. Never import non-ENSURE via the batch path. Anonymous matching stays out of listening history.

Import REPLACE adds the entire desired suffix with `DEDUPE_OPTION_SKIP` in one edit after any required removal, journals ambiguous failures for fresh snapshot reconciliation, then verifies all pages before completion. Expose progress including completed confirmation entries. Retain legacy APPEND compatibility for old hosts. Raise only Spotify playlist reads to 100.

- [x] Add/run failing bounded concurrency, ordered errors, overlap, strategy, bulk/duplicate/order and playlist pagination tests.
- [x] Implement operations and version increments; run provider and SDK checks/tests.
- [x] Build and verify both new provider packages using the existing CLI/author identity workflow without uploading.

## Task 4: Runner and percentage UI

**Files:** `PlaylistMirrorRunner.kt`, `PlaylistMirrorModels.kt`, pure progress helper, `TvCatalogPageScreen.kt` or sibling components, default strings and affected mirror/UI tests.

**Interfaces:** Consume Task 1 batch result and import progress. Batch-capable targets combine ENSURE with matching; complete any remaining ENSURE cursors after the match rounds. Remove per-track APPEND from the runner. Persist all ordered results before advancing each source position. Confirm the source before the final single REPLACE. Keep resuming/same-destination replacement/account changes safe.

Progress phases: source loading starts at 0; matching accounts for processed matched+missed tracks up to 85%; writing is 85–95%; verification is 95–99% using provider-confirmed counts; only ready reaches 100%. Existing or resumed work must not show imported-count plateaus. Preserve missing-track visibility and existing ready/error actions.

- [x] Add/run failing runner tests for batch call counts, true overlap, no APPEND, duplicates, retries, resumes, stale source/account/package changes and empty lists.
- [x] Implement runner and pure progress policy; run focused tests.
- [x] Consult official Material 3 progress docs, update the UI and English resource text, and verify device behavior.

## Task 5: Integration and PRs

- [x] Run public contract/schema/SDK checks, affected unit tests, ktlint/Spotless and flavor-prefixed Github/Foss compilation; build the relevant app flavor.
- [x] Run all private provider tests/checks and plugin package verification; retain build evidence and version metadata.
- [x] Exercise a typical 50–100-track playlist on Android, including percentage progress, cancel/resume, ready playback, and an error path; record measured scope and limitations.
- [x] Update graphify AST state in the app worktree, review each task and the whole diff, fix material findings, and avoid secrets/generated package binaries in commits.
- [x] Commit conventional messages, push feature branches, open linked app and private-plugin PRs with exact validation results and pending deployment requirements.

## Execution ledger

- Created isolated app and provider worktrees from current main. The explicit build-and-PR goal authorizes implementation and PR creation.
- Completed Tasks 1–4 with failing tests observed before the behavior changes. The native contract, generated SDK/schema, cache-aware batching, scorer, providers, runner and percentage UI are implemented.
- Native batches keep serialized QuickJS ownership and overlap searches with ENSURE inside one root. The original caller is checked before additional strategies, including account/package validity.
- A separate key in the existing Room cache distinguishes batch-confirmed misses from primary-only misses. This preserves fallback eligibility without a schema migration.
- Independent API/reliability and cross-area reviews identified cancellation, miss provenance and remastered-version edge cases. Regression tests reproduced them; fixes and focused re-review cleared the findings.
- Spotify 0.2.3/code 7 and YouTube Music 0.2.3/code 8 were built, signed and verified with the original pinned author. Generated contracts in both repos are identical; package binaries stay ignored.
- Full app unit suite passed after rebasing onto latest main: 1,243 tests, zero failures, one skip. Contract suite passed: 16 tests. Github/Foss Kotlin compilation, debug app/test APK builds, ktlint and public SDK checks passed.
- Private checks passed with 303 tests and 11 intentionally skipped live tests. No publication or live Spotify REST investigation occurred.
- Seven Android 9/AM6 tests passed using real signed providers, QuickJS and Room with intercepted synthetic HTTP. They verify 16-search/ENSURE overlap, 100 ordered occurrences in one duplicate-preserving edit, conditional Unicode fallback, transient error caching/retry, cancellation/resume and readiness-gated handoff.
- Android verification caught unsupported pre-Android-10 Unicode script syntax. The scorer now uses `sc=Latin`, supported by the Android Pattern API, and has a device regression test.
- The Android harness uses an isolated registry and in-memory MirrorStorage. Production coordinator persistence and live ExoPlayer decoding are not claimed by this harness.
- Lifted the status leaf to an internal sibling component to keep the screen within its size target and test the actual Compose labels, retry action and rendered progress. The actual Compose test passed on Android 9, covering loading/matching/writing/99%-verification/ready/error/retry. The 99% screenshot was inspected; the UI test wakes and restores the device screen state.
- `graphify update .` completed AST-only: 903 files, 7,933 nodes, 12,351 edges. A final refresh follows the remaining edits.
- Rebased onto latest main, reran all app checks and eight Android integration/Compose tests (12.580s total), inspected the progress screenshot, pushed clean feature branches and opened linked app/provider PRs.
- App PR: https://github.com/johnneerdael/Milkbeat/pull/28
- Provider PR: https://forgejo.thepi.es/jneerdael/Milkbeat-Plugins/pulls/4
- Both PRs are open and mergeable. Hosted build/security checks were still running at the final local verification; provider packages were built and verified locally without publication.
