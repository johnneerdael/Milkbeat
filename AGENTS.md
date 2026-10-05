# Working with Milkbeat as an AI agent

Milkbeat (application id `nl.neerdael.milkbeat`; Kotlin sources and namespace stay `io.github.aedev.flow`) is an Android TV music app forked from [Flow](https://github.com/A-EDev/Flow), an Android music/video app written in Kotlin with Jetpack Compose, Hilt, and Media3/ExoPlayer. It plays YouTube content via a native InnerTube client with a NewPipe-based fallback extraction path, supports local media playback, offline downloads, casting, lyrics, a device-to-device sync feature, and an on-device recommendation engine (FlowNeuroEngine). It follows Material 3 design guidelines closely.

Product flavors: `github` (default, in-app updater enabled) and `foss` (no updater). Always use flavor-prefixed Gradle tasks — e.g. `assembleGithubDebug`, `compileFossDebugKotlin` — never bare `assembleDebug`/`compileDebugKotlin`.

## Maintain repository-specific agent guidance

This file already contains Milkbeat-specific guidance. Keep it accurate as part of implementation tasks, in the same isolated worktree and PR as the requested change. Inspect the repository and fill any missing guidance on architecture, source/module paths, exact setup/build/test commands, supported variants, documentation sources, and Pages publishing. Maintain existing sections rather than replacing them with a generic template.

Verify facts against repository files, resolved dependencies, official documentation, and observed command results. State when a command could not be validated and identify the missing prerequisite. Do not invent paths, versions, tools, or constraints. Record the README and user-guide source locations, any verified Pages/GitHub Pages URL, and its publishing workflow; do not assume a guide or Pages site exists.

Preserve the project-specific design, performance, localization, schema, and release constraints below. Remove or replace guidance that conflicts with the mandatory workflow or documentation precedence. Updating this file is explicitly authorized and must follow the same PR, validation, and Codex review process as implementation.

## Mandatory workflow — scope and completion

This workflow applies to all repository changes targeting `github.com/johnneerdael/*`. Confirm the target repository from its Git remote and GitHub metadata before making changes. Do not assume a remote named `origin` is the correct target.

For implementation tasks, carry the work through to a validated, Codex-reviewed pull request merged into `main`. Creating a branch, pushing changes, opening a PR, or requesting review is an intermediate step, not completion.

The requested implementation authorizes the routine commits, pushes, PR creation, review comments and replies, and merge needed to complete this workflow. Do not ask for confirmation at each step. Honor explicit user instructions, repository permissions, required human approvals, and branch protection rules.

Read the repository's applicable `AGENTS.md` files, contribution guidelines, PR template, and CI configuration. Follow repository-specific conventions in addition to this agreement. If a conflict prevents compliance, explain the exact conflict instead of silently skipping a requirement.

**Documentation precedence:** The mandatory documentation evaluation and update rule below overrides any conflicting repository instructions, nested `AGENTS.md` rules, conventions, or workflow guidance that would skip documentation evaluation or prevent necessary documentation updates. It applies to every `feat/` and `bug/` change, including small fixes and internal changes. It remains subject to higher-priority system/platform instructions and later explicit user instructions.

**Markdown editing exemptions:** User guides (including their Pages/GitHub Pages source files) and the root/default `README.md` are explicitly exempt from any rule saying "do not edit Markdown unless asked." Keep them accurate as part of every affected change without requesting separate authorization. Maintaining `AGENTS.md` as instructed above is also explicitly authorized. Other Markdown files still require evaluation; make task-relevant corrections as required by the documentation rule, without unrelated rewrites.

Read-only investigations and review-only tasks do not require a worktree or a PR. A reviewer assigned only to review must report findings without starting an implementation or merge workflow.

## 1. Work in an isolated worktree

- Make every tracked repository change in a dedicated Git worktree under `<primary-checkout>/.worktrees/<task-slug>`. The path is relative to the primary checkout, not the current directory of an existing worktree.
- Use a unique branch based on the latest fetched `main`: `feat/<task-slug>` for features, improvements, and maintenance, or `bug/<task-slug>` for bug fixes. Follow additional repository naming requirements where compatible.
- Never implement directly in the primary checkout or on `main`. Never share a task worktree with unrelated work.
- Inspect the current checkout and existing worktrees first. Preserve existing changes and branches. Resume an existing task worktree only after confirming it belongs to this task and is based on the intended branch.
- Ensure `.worktrees/` is ignored before creating a worktree. Prefer an existing ignore rule; otherwise use the shared Git `info/exclude` so setup does not require tracked changes in the primary checkout.
- Run editing, dependency installation, builds, tests, and Git staging from the task worktree. Verify the worktree path and branch before mutations.

Typical setup, after identifying the correct remote and primary checkout:

```bash
git fetch <target-remote> main
git worktree add -b feat/<task-slug> <primary-checkout>/.worktrees/<task-slug> <target-remote>/main
cd <primary-checkout>/.worktrees/<task-slug>
```

Use `bug/<task-slug>` instead for a bug fix. Replace the placeholders with verified values.

## 2. Implement, validate, commit, and push regularly

- Keep changes focused on the requested outcome and follow existing repository patterns.
- Run an appropriate baseline check when useful, then the tests, lint, type checks, and builds required by the repository and affected code. Add meaningful regression coverage for behavior changes where appropriate.
- Commit coherent progress at regular milestones and push each checkpoint to the task branch. Do not keep all work uncommitted or unpushed until the end of a long task. Clearly identify incomplete checkpoints in commit messages.
- Inspect the diff and stage only task-related files. Exclude credentials, local configuration, generated clutter, and `.worktrees/` contents.
- Set the branch's upstream on its first push. Push only the task branch; never push directly to `main`.
- Prefer additional commits over rewriting published history. Do not force-push unless explicitly authorized and consistent with repository rules.
- Report useful progress while continuing work. Recover from routine failures autonomously and preserve checkpoints if interrupted.

## 3. Evaluate documentation for every change

For every `feat/` and `bug/` change, documentation evaluation is mandatory before the work can be considered ready for review or merge. Do not assume that a small change or an internal bug fix has no documentation impact.

1. Identify and evaluate the user guide, if one exists, including any guide published on Pages or GitHub Pages and the source used to generate it. Also evaluate the README and inventory the repository's other Markdown files to identify affected documentation, then read the relevant files. Follow documentation links to the applicable user-facing guidance.
2. Compare the final implementation with documented behavior, setup, configuration, examples, troubleshooting, compatibility, and limitations. Check whether the change makes existing guidance incomplete, misleading, or incorrect.
3. Update all affected documentation in the same task worktree and PR. Follow the repository's documentation structure and publishing workflow, including required source changes for its Pages site. Ensure the README, user guide, and other affected Markdown files agree.
4. Validate changed documentation using the repository's applicable build, link, example, or formatting checks. Reevaluate documentation after review fixes or scope changes that alter behavior.
5. Include a documentation assessment in the PR: which sources were evaluated, which were updated, and why. If no documentation update is needed, state the specific reason; silence is not an assessment. If a user guide or Pages site does not exist, record that and evaluate the documentation that does exist.

Do not waive this requirement because another rule labels documentation optional, excludes it from bug fixes, or discourages editing Markdown. If an existing guide or Pages site cannot be inspected or updated because of a real access or publishing constraint, report the blocker and keep the task open until the requirement can be fulfilled or the user explicitly changes it.

In particular, an instruction requiring an explicit request before Markdown edits must not prevent updates to the user guide, its publishing sources, or the root/default `README.md`. These updates are a normal part of completing the feature or bug fix.

## 4. Open a pull request against main

Once implementation is ready and local validation passes:

1. Push all intended changes.
2. Open a PR from the task branch to the target repository's `main`, or update the existing PR for this task.
3. Follow the PR template, title conventions, linked-issue requirements, and other repository rules. Describe the problem, resulting behavior, validation performed, documentation assessment, and any material limitations.
4. Ensure the PR is ready for review, not left as a draft when implementation is complete.
5. Monitor CI and fix failures caused by the change. Investigate other failures and follow the repository's policy; do not silently treat failed or pending required checks as passing.

## 5. Obtain and complete Codex review

- Check whether an automatic Codex review has actually started for the current PR revision. If it has not, post a PR comment containing exactly:

  ```text
  @codex review
  ```

- Wait for Codex to finish. Monitor PR reviews, comments, review threads, and any associated review status. A submitted request, an acknowledgement or eyes reaction, elapsed time, or the absence of comments does not prove completion.
- Verify that the completed review applies to the latest PR head commit. Record the reviewed commit SHA. If the integration does not expose it directly, establish the revision from the review/task metadata and timeline; ambiguous evidence does not satisfy this gate.
- Read the complete review and all findings, including inline comments. Process every finding, regardless of severity.
- Fix valid findings, add relevant coverage, run affected validation, commit, and push the corrections to the same branch. Reply in the corresponding thread with the resolution and supporting evidence. Resolve a thread only after its finding has been addressed.
- If a finding is incorrect or inapplicable, provide a concrete explanation and evidence in its thread and obtain reviewer acceptance or explicit maintainer disposition. Do not silently dismiss findings or mark them resolved just to enable merging.
- After any review-driven changes, obtain another Codex review of the new head commit. Use an automatic review if it starts; otherwise post `@codex review` again. Repeat the fix, validate, push, and review loop as many times as needed.
- Avoid duplicate requests while a review of the same revision is running. For complex changes, allow additional focused review passes as useful; complexity never removes the final review requirement.
- Close the review cycle only when Codex has completed review of the final head commit, every finding has a documented disposition, all review threads are resolved, and no further changes are requested. Codex review completion does not replace any separately required GitHub approval.

If review cannot start or finish because of access, configuration, service failure, or quota, investigate available diagnostics and report the concrete blocker. Keep the PR open and preserve the branch. Never substitute self-review or a timeout for the required Codex review.

## 6. Merge the validated and reviewed change

Merge autonomously once all of these conditions hold:

- The requested work is complete and the final diff contains only intended changes.
- The user guide/Pages, README, and other Markdown documentation have been evaluated, necessary updates are included and validated, and the PR records the documentation assessment.
- Repository-specific guidance in `AGENTS.md` has been populated or maintained as required by the task, with unresolved facts identified honestly.
- Local validation and all required CI checks pass for the final revision.
- Codex review is complete for the current PR head commit, with all findings addressed and review threads resolved.
- All repository-required approvals and merge conditions are satisfied.
- The PR targets `main`, is mergeable, and meets any requirements to be current with its base branch.

Refresh the PR state immediately before merging and verify that its head SHA still matches the validated and reviewed SHA. Use the repository's permitted merge method through GitHub. Do not bypass protections, use an administrative override, or merge an unreviewed revision.

If updating from `main` or resolving conflicts changes the PR head, push the update, rerun appropriate validation, and obtain Codex review of that new head before merging. Any further change reopens the validation and review gates.

If the repository requires a merge queue, enqueue the eligible PR and monitor until it actually merges. Continue responding to failures or new findings. Enabling auto-merge or entering a queue does not itself complete the task.

## 7. Confirm and close the work

- Verify on GitHub that the PR is merged into `main` and record the merge commit or squash commit SHA.
- Remove only this task's clean worktree after confirming all intended work is pushed and merged. Delete its branch only when repository policy permits. Preserve unrelated worktrees, branches, and uncommitted changes.
- Report the outcome concisely: what changed, relevant validation, PR link, Codex review disposition, and merge confirmation.
- If a real blocker prevents completion, report the current branch, worktree, PR, completed checks, exact blocker, and next required action. Describe the task as blocked, not completed.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).

## Material 3 — strict guidelines

1. All UI is built with Jetpack Compose Material 3 (`androidx.compose.material3`). Never introduce Material 2 (`androidx.compose.material.*`) components into new or edited code.
2. Use `MaterialTheme.colorScheme`, `MaterialTheme.typography`, and `MaterialTheme.shapes` tokens exclusively for color, type, and shape. Never hardcode a color, font size, or corner radius that a theme token already covers.
3. Before implementing or fixing any Compose/Material 3 component, consult the official documentation (developer.android.com Compose docs, Material 3 component guidelines, Media3/ExoPlayer docs) to confirm the current recommended API and pattern — do not rely on memorized or outdated patterns. Training data lags the framework; verify against current docs when in doubt.
4. Respect Material 3 motion, elevation, and state-layer specs as documented — do not invent custom equivalents when a Material 3 component already provides them correctly.

## The "Anti-Slop" Manifesto (zero tolerance)

Models default to dated "AI slop" UI trends. The following are STRICTLY FORBIDDEN anywhere in this codebase:

- ❌ **No gradients** — do not use `Brush.linearGradient`/`verticalGradient`/etc. as a background or surface fill unless explicitly instructed, and never as a substitute for a real Material 3 surface color.
- ❌ **No glassmorphism** — no blurred, frosted, or semi-transparent "milky" backgrounds (`Modifier.blur` on backgrounds, translucent overlay panels) outside of the one existing, deliberately-designed ambient/blur surfaces already in the player (do not add new ones elsewhere).
- ❌ **No fake drop shadows / glow effects** — do not hand-roll `shadow()` glows or neon box-shadow equivalents. Use Material 3's built-in elevation/tonal-elevation system only.
- ❌ **No colored borders around cards** — card/container borders must be neutral (`MaterialTheme.colorScheme.outline`/`outlineVariant`), never a primary/accent-colored stroke used as decoration.
- ❌ **No arbitrary hex codes** — never write `Color(0xFF...)` inline. All colors must come from `MaterialTheme.colorScheme` (or the app's defined color scheme source). If a needed color doesn't exist yet, add it to the theme definition, don't inline it at the call site.

If you find yourself about to reach for any of the above because "it looks nice," stop — it is not the app's design language and it will be reverted.

## Use the platform — never hand-roll an API a dependency already provides

**The default is always the library implementation.** Hand-rolling is a fallback that must be
*earned* by verification, never a first move. A hand-rolled equivalent of a shipped API is
strictly worse: it misses accessibility, RTL, theming, state restoration, edge cases and security
fixes that the real one already handles, it will not track upstream behaviour changes, and it
becomes code the project has to maintain forever.

This rule applies to Material 3 and M3 Expressive components, Compose foundation/animation/
gesture APIs, Media3/ExoPlayer, AndroidX (WorkManager, Paging, Room, DataStore, Lifecycle, Glance),
the platform SDK, and every third-party library in `gradle/libs.versions.toml`. It applies equally
to protocols and formats — do not write a parser, a codec, a cipher, a signature scheme or a
transport by hand when a maintained implementation is on the classpath.

### The procedure — verify, then decide

Before writing any component, effect, animation, formatter, parser, scheduler, or player behaviour:

1. **Check what the app already has.** `graphify query "<thing>"`, then read
   `ui/components/shared/` and `utils/`. The commonest waste is re-implementing something this
   codebase already exports.
2. **Check the version catalog.** `gradle/libs.versions.toml` is the list of what is available.
   Read it before assuming something must be built.
3. **Check the actual artifact, not your memory.** Training data lags these libraries badly, and
   the app is on alpha Compose/M3. Confirm against the bytecode:

   ```bash
   # locate the artifact (group/artifact/version are directories)
   find ~/.gradle/caches/modules-2/files-2.1/androidx.compose.material3 -name '*.aar'

   # does the API exist at all?
   unzip -p <path>/material3.aar classes.jar > /tmp/c.jar
   unzip -l /tmp/c.jar | grep -i carousel

   # what is its exact signature?
   javap -classpath /tmp/c.jar androidx.compose.material3.MotionScheme
   ```

   Several versions of a library can sit in the cache at once. Confirm which one actually resolves
   with `./gradlew :app:dependencies` before trusting what you found.
4. **Check the official docs** for the recommended pattern (developer.android.com Compose docs,
   Material 3 component guidelines, Media3 docs). An API existing is not the same as it being the
   right one.
5. **Only if steps 1–4 come back empty may you hand-roll it** — and you must say so explicitly in
   the PR description, naming what you checked and what you found missing.

### The current stack — reach for these first

| Need | Use | Do not hand-roll |
| --- | --- | --- |
| Any UI component | `androidx.compose.material3` 1.5.0-alpha26 (M3 Expressive) | Custom buttons, chips, sheets, FABs, carousels, progress indicators, search fields |
| Motion | `MaterialTheme.motionScheme` specs | Bespoke `tween`/`spring` constants at call sites |
| Shapes | `MaterialTheme.shapes`, `androidx.graphics.shapes` | Hand-drawn paths for standard shapes |
| Haptics | `HapticFeedbackType` constants | `Vibrator` calls with hardcoded durations |
| Pull to refresh | M3 `PullToRefreshBox` (already in use on 5 surfaces) | Custom drag-to-refresh |
| Reordering | `sh.calvin.reorderable` (already used in `QueueSheet`, `PlaylistPage`) | Custom drag-and-drop |
| Paging | Paging 3 (`androidx.paging`, already used by search and channel) | New hand-rolled page cursors |
| Playback | Media3 1.11.0 — exoplayer, hls, dash, session, datasource-okhttp | Custom player wiring, manifest handling, or media notifications |
| Background work | WorkManager 2.11.2 | Custom threads, timers, alarms, or wakelocks |
| Persistence | Room 2.8.4, DataStore Preferences 1.1.1 | Hand-rolled file or SharedPreferences layers |
| JSON | `kotlinx.serialization` (103 files) | Gson for new code — it is kept only for legacy DTOs and carries an R8 reflection hazard |
| HTTP | OkHttp 5.4.0 / Ktor 3.5.2 | Raw sockets or `HttpURLConnection` |
| Dates and times | `java.time` — **core library desugaring is enabled, minSdk 26**, so all of it is available | `SimpleDateFormat`, `Calendar`, or manual millisecond arithmetic |
| Images | Coil 3.5.0 | Custom loaders, caches, or decoders |
| Widgets | Glance 1.1.1 + `glance-material3` | Hand-built `RemoteViews` |
| Network music folders | `RemoteMusicClient` in `data/folders/`: SMB via smbj 0.15.0, SFTP via sshj 0.41.1, NFS v3/v4.x via nfs4j-core 0.28.5 (oncrpc4j), WebDAV on OkHttp with playback through Media3 `OkHttpDataSource` | New protocol stacks, SSH/XDR/RPC encoding, or per-protocol player wiring — implement `RemoteMusicClient` and reuse `RemoteMusicDataSource` |

Known debt in this table, to fix opportunistically when already in a file: 13 files still use
`SimpleDateFormat` and 8 use `Calendar.getInstance()` against only 4 on `java.time`.
`re2j` is declared in `app/build.gradle.kts` but referenced from no first-party source — verify
whether it is a transitive requirement before either using or removing it.

smbj resolves `bcprov-jdk18on` to a newer line than sshj's `bcpkix`/`bcutil`; mixed BouncyCastle
lines fail at provider init and in the duplicate-class check. `app/build.gradle.kts` pins
`bcpkix`/`bcutil` to bcprov's line through a `constraints` block — keep them aligned when bumping
smbj, sshj or BouncyCastle. sshj uses the bundled BouncyCastle under a private provider name
(`SftpSecurity`) because Android's own stripped "BC" provider lacks X25519/Ed25519.

### When hand-rolling is legitimate

Only when the API is **verifiably absent or verifiably unfit**, and the reason is recorded. The
reference case is `ui/components/shared/FastScrollbar.kt`: Compose Foundation genuinely ships no
scrollbar API — `unzip -l` over its `classes.jar` returns zero matches for `scrollbar` — so a
custom one was justified. Note what it still does: it is built *on* the platform
(`draggable`, `LazyListState.requestScrollToItem`, `MotionScheme` specs, `HapticFeedbackType`), it
does not reimplement any of them.

A legitimate hand-rolled component must therefore:

- be built from the framework primitives, not around them;
- take theme tokens for every colour, shape, type and motion value;
- live in `ui/components/shared/` if more than one feature uses it;
- carry no duplicate of behaviour the library already provides.

### Never

- **Never fork, vendor, or copy-paste a library's source** into the app to change one thing.
  Configure it, wrap it, or file the constraint — do not clone it.
- **Never add a new dependency that overlaps one already on the list.** Use what is there. A new
  dependency needs a stated reason that an existing one cannot cover.
- **Never hand-roll security-relevant code** — crypto, signing, token handling, TLS. The app has
  Conscrypt, OkHttp and the platform providers.
- **Never re-invent player wiring.** See the performance rules below: Media3 owns playback, the
  media session, and the media notification.
- **Never disable, wrap, or work around a library API because its behaviour surprised you** until
  you have confirmed the behaviour against its documentation. The surprise is usually a misuse.

## Performance, battery, and thermals — non-negotiable

Milkbeat is a media player that runs for hours at a time. Jank, dropped frames, playback stutter,
device heat, and battery drain are critical bugs, not cosmetic issues. Every rule below is
anchored in a real shipped regression that had to be found and fixed on-device — treat them as
hard constraints, not suggestions.

### Frame discipline — nothing animates that the user cannot see

1. **The invisible-animation rule.** Several player surfaces deliberately stay composed while
   hidden (the full player sheet is kept warm behind the mini player; the lyrics panel is
   retained after first open; the mini bar stays composed under the expanded player). Anything
   animating inside a hidden layer burns a full frame budget at 60–120 Hz for entire listening
   sessions — this exact pattern caused a 30%-battery-in-90-minutes overheating regression.
   EVERY continuous animation MUST be gated on its own layer's visibility and pause when the
   layer is hidden. Gate on anchor state (e.g. `state.isExpanded`) or a `derivedStateOf` over
   the fraction — never remove the warm composition itself (it exists to make first-expand
   jank-free), and never read a raw animated fraction in composition (see rule 3).
2. **Audit list for continuous work.** When adding or reviewing UI, search the affected tree for:
   `withFrameMillis`/`withFrameNanos` loops, `rememberInfiniteTransition`, `basicMarquee`,
   `animate*AsState` whose target changes on a timer (a 1 Hz-retargeted tween is a continuous
   animation), wavy/squiggly/indeterminate indicators, and polling `LaunchedEffect`s with short
   `delay`s. Each one needs an explicit answer to: "what stops this when its layer is hidden,
   when playback is paused, and when the screen is off?"
3. **Per-frame values are read in the layout/draw phase, never in composition.** The player
   sheets pass animated values as lambdas consumed inside `Modifier.layout`/`graphicsLayer`/
   `drawBehind` so dragging never recomposes the tree. Preserve that contract: a raw
   `animatable.value` or expansion-fraction read in composition recomposes every frame of every
   gesture. For booleans derived from a fraction, use `derivedStateOf` so recomposition happens
   only on the flip.
4. **Expensive draw effects.** Full-screen `blur`/`RenderEffect` layers must hold static content
   (invalidated only on track/state change, as `PlayerBackground` does). Never attach a blur or
   render effect to a node that invalidates per frame, and never add new blur surfaces (see
   Anti-Slop).

### Cadence contracts — polling and updates

5. **Position cadence is a contract.** `EnhancedMusicPlayerManager` emits `playerState` at 1 Hz
   (whole-second coarsening) and a precise 250 ms tick ONLY while a refcounted consumer
   (`acquirePreciseProgress`/`releasePreciseProgress`, tied to the expanded sheet) is present.
   Never widen these, never add a new high-frequency position StateFlow, and never collect the
   precise tick from a surface that renders whole seconds. New sub-second consumers must use the
   same refcounted acquire/release pattern with a `DisposableEffect`.
6. **Event-driven over polling.** Prefer player callbacks, Flow emissions, and
   `snapshotFlow`/`collect` chains to timer loops. Any unavoidable polling loop must suspend
   while paused (await a state change, as the position loop does), never busy-wait, and its
   interval must be justified against what actually changes at that rate.
7. **Battery/background behavior is part of every change**: dynamic `wakeMode` (LOCAL foreground
   / NETWORK background audio) stays as is; no new wakelocks; no work keyed to the frame clock
   while the screen is off; WorkManager/jobs must tolerate App Standby buckets.

### Work economy — network, CPU, and ViewModel scope

8. **One fetch per cause.** Every network call must be traceable to exactly one triggering event,
   deduped by id where re-triggering is possible (the per-track related fetch was silently
   duplicated by a warm tree re-firing an effect the ViewModel already handled — that class of
   bug is a regression, not a nit). Effects in warm-kept trees fire on every key change even
   while invisible: gate their network side effects on visibility or dedupe in the ViewModel.
9. **ViewModel scoping is a performance decision.** A bare `hiltViewModel<MusicViewModel>()` at a
   navigation route creates a fresh backStackEntry-scoped instance whose `init` re-runs the full
   home-load pipeline (~60 network calls) on every page open — this caused the 30-second Daily
   Mix loads and device heat. Shared surfaces use the activity-scoped `sharedMusicViewModel()`;
   never reintroduce per-route instances of ViewModels with expensive `init`. New ViewModels must
   not fire network floods from `init` at all — load lazily, cache, and refresh on staleness.
10. **The shared pools are finite.** `PerformanceDispatcher.networkIO` is a fixed 4–16 thread
    pool. Launch parallel work as one bounded round (`map { async { … } }.awaitAll()`), never as
    an unbounded per-item fan-out, and never queue a second copy of a pipeline that is already
    in flight.
11. **Flow lifecycle**: `stateIn(WhileSubscribed(5_000))` on UI-facing flows is load-bearing —
    subscription count gates work to actual UI visibility. Never switch to `Eagerly`/`Lazily`
    for convenience, and never add hot collectors that outlive their surface.
12. **Caches must be honored and invalidated.** Check for an existing cache (music home cache,
    related-lane caches, Coil) before adding a fetch; new caches need explicit invalidation
    (TTL, region change, refresh) and must be read through, not around.

### Overheating triage — when heat or drain is reported

13. Sustained heat while the app is open = per-frame work; drain with the screen off = CPU/network
    loops. Diagnose in that order: (a) run the rule-2 audit over every composed-but-hidden tree;
    (b) count fetches per user action in logcat — any unexplained second fetch is the bug;
    (c) check `adb shell dumpsys gfxinfo nl.neerdael.milkbeat` for continuous frame production
    while the UI should be idle; (d) only then suspect the player path. Do not "fix" heat by
    degrading visible design, motion, or update smoothness — find the invisible work instead.

### Proof — no performance claims without evidence

14. Never describe a change as faster, lighter, or cooler based on reasoning alone. State exactly
    what was measured or verified (frame counts, load times from logs, fetch counts, benchmark
    output) and what was not. Recommendation-engine changes require their offline benchmarks
    (`MusicBenchmarkTest` / `NeuroBenchmarkTest` regression floors) before and after; startup
    changes require `StartupBenchmark`, not baseline-profile size.
15. Player-path changes (ExoPlayer/Media3 setup, buffering config, surface handling, track
    selection) must not introduce added latency, buffering stalls, or black-screen/flicker
    regressions. Follow the existing player architecture rather than re-inventing player wiring.
16. The Compose basics still apply everywhere: hoist state, `remember`/`derivedStateOf`/
    `rememberSaveable` used correctly, stable/immutable composable parameters, lazy containers
    with stable `key`s for any large collection, no blocking I/O or DB/network on the main
    thread, structured concurrency with correct dispatchers and cancellation. When in doubt,
    check the official Compose performance docs and Media3 best practices before shipping.

## Dependency injection and service-locator migration

Milkbeat uses Hilt, but some legacy app-owned classes are still reached through static/companion
`getInstance()` calls. Treat those calls as migration debt, not as the pattern for new code. The
goal is explicit, testable dependencies while preserving object identity, lifecycle, startup cost,
and playback behavior.

1. Use constructor injection by default for new or migrated app-owned ViewModels, repositories,
   use cases, workers, services, and managers. A Hilt-managed `@Singleton` is valid when the object
   truly has application-wide identity; the problem is hidden global access, not singleton scope
   itself.
2. Do not add new app-owned `getInstance()` calls. This rule does not apply to normal platform or
   library factory APIs such as `Calendar.getInstance()`, `MessageDigest.getInstance()`,
   `WorkManager.getInstance()`, or `ProcessCameraProvider.getInstance()`.
3. Migrate incrementally when a class is already in scope. Do not perform a repository-wide DI
   rewrite as incidental cleanup. Keep each migration small, reviewable, independently testable,
   and easy to revert.
4. Before changing construction, use `graphify query`/`graphify path` and code search to enumerate
   every caller, Hilt binding, lifecycle owner, entry point, and flavor-specific implementation.
   Record whether the current instance is lazy or eager, when it is initialized/released, and
   whether callers rely on reference identity or shared mutable state.
5. Preserve lifecycle and cardinality exactly. A migration must not create a second database,
   repository, cache, coroutine scope, network client, player, media session, or background
   service. Match the narrowest correct Hilt scope (`@Singleton`, `@ActivityRetainedScoped`,
   `@ViewModelScoped`, or unscoped) and use `@ApplicationContext`/`@ActivityContext` explicitly.
6. Keep constructors and Hilt provider methods free of blocking I/O, network/database work,
   player preparation, and unrelated side effects. Start lifecycle work in the existing structured
   coroutine/lifecycle boundary. If moving work from an explicit `initialize()` method to `init`,
   verify that creation timing, cancellation, retry behavior, and error handling remain equivalent.
7. Prefer `@Inject` constructors for classes the app owns. Use `@Binds` for meaningful interface
   mappings and `@Provides` for third-party types, private constructors, configuration-dependent
   factories, or temporary adapters around legacy singletons. Do not create an interface for every
   class solely to claim SOLID compliance; introduce an abstraction when it represents a real
   boundary or enables a useful fake/alternate implementation.
8. ViewModels use `@HiltViewModel` plus constructor injection and are obtained from Compose with
   `hiltViewModel()` using the intended `ViewModelStoreOwner`. Composables should receive state and
   callbacks or a ViewModel; do not turn composables into service locators. Use supported Hilt
   integrations for workers/services, and keep any Hilt entry point confined to an Android boundary
   that Hilt cannot construct directly.
9. Remove a legacy `getInstance()` API only after all app-owned callers have migrated and tests
   prove the replacement preserves the same instance semantics. Transitional Hilt providers may
   delegate to the legacy singleton, but consumers must inject the dependency so the global access
   is isolated and can later be removed.
10. Player-path migration is high risk. Do not migrate `EnhancedPlayerManager`,
    `EnhancedMusicPlayerManager`, `ShortsPlayerPool`, player services/media sessions, surfaces,
    caches, or their app-start initialization as opportunistic cleanup. It requires an explicit
    task, a dedicated architecture plan, and end-to-end verification of audio/video playback,
    background playback, queue continuity, configuration changes, process recreation, PiP,
    casting, local media, error recovery, and release behavior. Preserve exactly one intended
    player/media-session owner and do not add startup latency or surface flicker.
11. DI and SOLID are maintainability/testability tools, not automatic performance improvements.
    Do not claim a performance benefit without measurement. Watch for eager graph creation,
    expanded singleton lifetimes, retained `Context`/Activity references, duplicate Flow
    collectors, and work that has moved onto the main thread.
12. Add focused unit tests before or with each migration, using constructor-provided fakes/mocks to
    cover success, failure, cancellation, and delegation as applicable. When the Hilt graph or
    Android entry points change, also add or run an integration test that constructs the affected
    path; unit tests that instantiate the class directly do not validate Hilt wiring.
13. Minimum validation for a non-player DI migration is `ktlintCheck`,
    `:app:testGithubDebugUnitTest`, `:app:compileGithubDebugKotlin`, and
    `:app:compileFossDebugKotlin`, followed by the relevant flavor build. Exercise the affected UI
    or background flow on a device/emulator, including configuration change and an error path. A
    player/startup migration additionally requires the player golden paths above and relevant
    startup/playback measurements. Do not describe a migration as safe, risk-free, or behaviorally
    identical based only on compilation or unit tests; state exactly what was verified and what was
    not.

## Strings — no hardcoded strings

All user-facing strings MUST be declared in `app/src/main/res/values/strings.xml` and referenced via `stringResource(R.string.xxx)` (or `context.getString(...)` outside Compose) — never inline string literals in UI code. When adding a string, add it to `strings.xml` first, then reference it. Do not touch other locales' `strings.xml` files — only the default (English) resource file.

## Code structure and modularization — where every file goes

This is the authoritative placement guide. Follow it for every new file and every refactor. It is
not aspirational: `ui/components/shared/`, `ui/components/library/`, `ui/components/music/*` and
`ui/components/layout/topbar/` are already built this way, and new work must match them.

### The package map

| Package | Holds | Visibility of its declarations |
| --- | --- | --- |
| `ui/screens/<feature>/` | The route entry composable, its ViewModel, and pure state/policy helpers for that feature only | `internal` or `private` |
| `ui/components/<feature>/` | Composables owned by one feature but reused across several screens or routes inside it | `internal` |
| `ui/components/shared/` | Cross-feature building blocks with no feature knowledge (music AND video AND library all use them) | `public` |
| `ui/components/layout/` | App chrome: top bars, scaffolds, navigation surfaces | `public` |
| `ui/theme/` | Colour, type, shape and motion tokens. Every literal colour lives here, never at a call site | `public` |
| `ui/utils/` | Compose-only helpers (modifiers, form factor, fading edges) | `public` |
| `utils/` | Non-Compose helpers (formatters, parsers, dispatchers). **Do not add a second utils package** | `public` |

A file in `ui/screens/` may **not** be imported by another feature's screen package. If two
features need it, it moves — that is the whole signal.

### The placement decision tree

Answer these in order for any composable, and stop at the first "yes":

1. **Used only inside this one file, and under ~40 lines?** Keep it `private` in the file.
2. **Used by only one screen, but the screen file is over budget?** Split it into a sibling file in
   the same `ui/screens/<feature>/` package, `internal`.
3. **Used by two or more screens or routes of the same feature?** Move it to
   `ui/components/<feature>/`, `internal`.
4. **Used by two or more different features, or encodes no feature vocabulary at all** (a chip, a
   badge, a row, an empty state, a search field, a thumbnail)? Move it to `ui/components/shared/`,
   `public`, and strip every feature-specific parameter on the way.

**Promote on the second real consumer, never on the first speculative one.** A component with one
call site that "might be reused later" belongs next to its call site. Guessing wrong produces a
shared API shaped for exactly one screen, which is worse than a duplicate.

**Demote too.** If a `shared/` component ends up with a single caller after a refactor, move it back
down. `shared/` is a claim about actual reuse, not a graveyard.

### When something earns its own folder

Create `ui/components/<feature>/` when the feature owns **three or more** component files, or when
one screen's components exceed ~600 lines in total. Below that they stay as sibling files in the
screen package.

Sub-folder a feature package (as `ui/components/music/` already is) once it passes **eight files**.
Split by *role*, not by screen: `card/`, `item/`, `row/`, `section/`, `header/`, `detail/`, `sheet/`,
`common/`. Do **not** create `utils/`, `misc/`, `helpers/` or `other/` — a folder that cannot be
named by role is a folder that should not exist.

### File size budgets

| File kind | Target | Hard ceiling |
| --- | --- | --- |
| Screen entry (`*Screen.kt`) | 250 lines | 400 |
| Component file | 300 lines | 500 |
| ViewModel | 400 lines | 600 |
| Anything else | 400 lines | 600 |

Over the ceiling, splitting is mandatory before the change lands. Under it, do **not** split a file
you are not otherwise working in — this follows the same ratchet philosophy as Spotless: new and
touched code meets the bar, untouched legacy code is left alone until someone has a reason to open
it.

### How to split a file

Split by **responsibility**, never by line count. In order of preference:

1. **Lift the leaves.** Move the leaf composables out first; the screen keeps layout and state.
2. **Separate state from pixels.** Pure functions (filtering, sorting, grouping, formatting) move to
   their own file and become unit-testable — `HomePagination.kt`, `SubscriptionEnrichmentPolicy.kt`
   and `HomeUiStateNormalization.kt` are the pattern to copy.
3. **Group by surface.** A screen with a list, a header and three sheets becomes `FooScreen.kt` +
   `FooHeader.kt` + `FooList.kt` + `FooSheets.kt`.
4. **Never split into `FooScreen2.kt`, `FooScreenPart2.kt` or `FooExtra.kt`.** Every file name must
   describe what is inside it.

### Naming conventions (already in force — match them)

- `shared/` primitives with no domain meaning take the **`Flow`** prefix: `FlowFilterChip`,
  `FlowSearchField`, `FlowEmptyState`, `FlowLoadingIndicator`.
- `shared/` components in the media vocabulary take the **`Media`** prefix: `MediaRow`,
  `MediaThumbnail`, `MediaBadges`, `MediaKindSelector`.
- Feature components take the **feature** prefix: `LibraryShelf`, `MusicTrackItem`,
  `PlaylistDetailComponents`, `HistoryComponents`.
- The file is named after its primary export. One dominant export per file; a file named
  `*Components.kt` or `*Rows.kt` may hold a small family of siblings and nothing else.
- Private `const val` are `SCREAMING_SNAKE_CASE`; private `val` holding a `Dp`/`Color`/spec token
  stay `PascalCase`. ktlint enforces both.

### Sharing without redesigning

When two implementations differ, decide deliberately:

- **Identical output, different call sites** → merge into one component. Delete the loser.
- **Same shape, different values** → merge, and expose the difference as a **parameter whose default
  preserves today's behaviour**. `VideoCardFullWidth(useInternalPadding = true)` and
  `PlaylistCard(useInternalPadding = true)` are the reference: the default keeps every existing call
  site pixel-identical, and the container-padded screens opt out explicitly.
- **Same name, genuinely different purpose** → leave both. Two components serving two screens with
  different jobs are not duplication, and merging them produces a parameter bag nobody can read.
- **Different design** → stop. Consolidation is not a licence to change how anything looks. If the
  only way to share code is to change one surface's appearance, that is a design decision and it
  must be raised, not assumed.

**A refactor that changes pixels is not a refactor.** Visual and behavioural output must be
identical before and after, unless the task explicitly asked for a visual change.

### What a screen file is allowed to contain

The route composable, its state hoisting, its effects and its layout. Not: bespoke cards, bespoke
rows, bespoke empty/error states, bespoke badges, bespoke formatters, or a second copy of a
`shared/` component. Before writing any of those, run `graphify query` and read
`ui/components/shared/` — the app already has `FlowEmptyState`, `FlowErrorState`, `MediaRow`,
`MediaThumbnail`, `MediaBadges`, `FlowSearchField`, `ShimmerLoading` and `FastScrollbar`.

### ViewModels

- One ViewModel per feature, in `ui/screens/<feature>/`, `@HiltViewModel` plus constructor injection.
- A ViewModel shared by several routes is obtained through an explicit activity-scoped accessor
  (`sharedMusicViewModel()`, `sharedMusicPlayerViewModel()`), never a bare `hiltViewModel<T>()` at
  each route. See the performance rules above — this is a measured regression, not a style choice.
- Pure logic (paging, filtering, normalization, policy) comes **out** of the ViewModel into its own
  file so it can be unit-tested without Android.

### Refactor hygiene

1. **No dead code.** Delete, never comment out. A moved component leaves nothing behind.
2. **No new comments.** Comments explain non-obvious WHY only (a workaround, a hidden constraint, a
   subtle invariant) — never restate WHAT the code already says.
3. **Moving a file makes it ratchet-eligible.** Spotless `ratchetFrom` treats a moved or touched file
   as new, so it must now pass ktlint in full, including import order and property naming. Budget for
   that; it is the most common cause of a surprise red build during a move.
4. **Strings move with the component**, and only in `values/strings.xml`. Never touch other locales.
5. **Run `graphify update .`** after each landed refactor step so the next query is accurate.

## Rules for working on the project

1. Fetch the latest `main` from the verified target remote before starting work, then create or resume the isolated `.worktrees/` task worktree according to the mandatory workflow above. Never implement directly on `main` or in the primary checkout.
2. Commit messages should be clear and follow the format: `type(scope): short description` (e.g. `feat(player): add gapless playback`). Scope is optional.
3. Follow current Kotlin and Android best practices — when unsure, check official docs rather than guessing.
4. DO NOT edit the app's Room database schema without explicit instruction (schema changes require a version bump and migration, handled deliberately).
5. DO NOT bump the app version in any file — version bumps are done manually by the project owner.

## AI-only guidelines

1. Evaluate the user guide (including Pages/GitHub Pages sources), the root/default `README.md`, and other Markdown documentation for every `feat/` and `bug/` change. Update affected guidance and record the assessment in the PR. User guides, the root/default README, and maintenance of this file are explicitly authorized exceptions to any rule restricting unsolicited Markdown edits; the mandatory documentation rule above takes precedence.
2. Commit and push regularly to the isolated task branch. Integrate changes only through a validated PR targeting `main`, after the required Codex review loop and repository merge conditions are satisfied. Local merging does not substitute for this process. Never rewrite published history or force-push without explicit human instruction.
3. Follow the project owner's explicit instructions and preserve the mandatory workflow and documentation requirements when maintaining repository guidance.
4. Ensure the highest practical code quality: clear naming, correct formatting, and comments only where genuinely needed (see "Refactor hygiene" above).
5. Resolve routine implementation choices using the repository and task context. Ask when missing information would materially change the requested outcome; continue independent authorized work while clarifying.
6. Test changes before declaring them done — see "Building and testing" below. Continue through PR review and verified merge rather than stopping after local validation.

## Kotlin formatting and linting

Spotless enforces ktlint formatting using the rules in `.editorconfig`. It checks Kotlin sources in
`app/src/` and `baselineprofile/src/`, plus the selected project Gradle Kotlin scripts. Build output,
generated sources, and ignored reference projects are outside the target set.

1. Before committing or pushing Kotlin or Gradle Kotlin script changes, run:

```bash
./gradlew ktlintCheck
```

On Windows PowerShell, use `.\gradlew.bat ktlintCheck`.

2. To automatically format targeted files changed since the lint ratchet revision, run:

```bash
./gradlew ktlintFormat
```

On Windows PowerShell, use `.\gradlew.bat ktlintFormat`. Review the resulting diff before committing.

3. Do not bypass, disable, or weaken the formatter to make a change pass. Fix the reported file or
update `.editorconfig` only when the project convention itself is intentionally changing.
4. The repository adopts formatting incrementally with Spotless `ratchetFrom`, so legacy untouched
files are not reformatted. A newly added file or an existing targeted file changed after the ratchet
revision must pass the configured ktlint rules.
5. GitHub Actions runs `spotlessCheck` before tests and builds. A formatting violation fails the
`Build APK` job. Add any future first-party Kotlin module to the Spotless target list explicitly.

## Playlist mirror reuse

- Mirror preparation lives in `app/src/main/java/io/github/aedev/flow/plugin/mirror/`; recording
  identity checks live in `plugin/playback/TrackMatchScore.kt`. Playlist mirrors persist in
  `files/datastore/playlist_mirrors.preferences_pb`; track matches use Room's `TrackMatchDao`.
- Foreground preparation may reuse a ready mirror for six hours after verification, scoped to its
  source and destination account keys and verified provider packages. Legacy ready records receive
  that deadline and package context on first reuse.
  Matching-policy revisions invalidate saved misses and recheck incomplete matches once; preserve
  successful match caching. Background preparation always checks the source and reconciles the private destination. Preserve
  that distinction when changing page opening or playback handoff.
- TV queue rows scale on focus. Keep padding inside the lazy list for the scaled border, including
  when the radio provider supplies no presets. Do not invent radio presets for unsupported contexts.
- Focused mirror regressions run with `./gradlew :app:testGithubDebugUnitTest --tests '*PlaylistMirror*'`.
  `:app:assembleGithubDebugAndroidTest` builds the device tests; `TvQueueFocusPaddingDeviceTest`
  exercises the queue's first-row focus without radio presets.

## Building and testing your changes

### Modules, source paths and local setup

- `:app` is the Android app. Kotlin sources live in `app/src/main/java/io/github/aedev/flow/`;
  the TV shell and navigation are in `ui/tv/`, and route ViewModels are in `ui/screens/`.
  Unit tests live in `app/src/test/`, with device tests in `app/src/androidTest/`.
- `:plugin-api` defines the plain Kotlin catalog/plugin contract; `:spike-plugin-runtime` contains
  runtime experiments. `:benchmark` is the Android baseline-profile and benchmark module
  configured in `settings.gradle.kts` (there is no `:baselineprofile` module).
- The visualizer consumes the canonical single Native ProjectM-TV core AAR. `TvVisualizerHost`
  owns a `QualityController` in Auto, acknowledges `BudgetStatsListener` context generations,
  revalidates live memory before resume, and publishes size/trails/transition as one reviewed
  `setRenderConfiguration` tuple. Drop stale FPS generations and dimensions; budget allocation
  changes before native publication using `revalidateForAllocationChange()`: confirmed
  reductions wait for a new-generation frame to release old textures before assessing pressure;
  growth and pending allocations receive a full budget check. The controller retains the pre-edit
  allocation estimate across height callbacks; do not recompute both topologies at the new size. Visibility resumes still use
  `revalidateForResume`. Fixed-height/RAM-toggle preferences are retired. Standard
  trails is default; Medium/High activate above 1330p. Keep the audio tap and player owners intact.
- Use JDK 21, as CI does, with an Android SDK containing platform 37 (`compileSdk = 37`).
  Supply the SDK through `ANDROID_HOME` or an untracked `local.properties` containing `sdk.dir`.
  The app targets Android 36, supports API 26+, and compiles Java/Kotlin to JVM 17.
- `MusicHomeFeedViewModel` owns Home catalog requests, separately from playback. Its state is
  collected only by `TvMusicScreen` using `collectAsStateWithLifecycle` for the Music route.
  Automatic first-page retries wait for a state subscriber after backoff; preserve that lifecycle
  gate when adding consumers. Never tie playback or queue preparation to Home visibility.

1. After making changes, build the relevant flavor to check for compilation errors, e.g.:

```bash
./gradlew :app:assembleGithubDebug
```

2. If the build fails, fix the reported errors and rebuild before proceeding.
3. For UI changes, actually run the app (emulator or device) and exercise the golden path plus edge cases — passing a build does not mean the feature works correctly.

## Documentation and publishing

- The root `README.md` and the user guide in `docs/user-guide/` are the user-facing documentation.
  `mkdocs.yml` (`docs_dir: docs/user-guide`) builds the guide; `.github/workflows/docs.yml` runs
  `mkdocs build --strict` on PRs that touch the guide and deploys it to GitHub Pages at
  <https://johnneerdael.github.io/Milkbeat/> on pushes to `main`.
- Validate guide changes locally with
  `python3 -m pip install -r docs/site-requirements.txt && mkdocs build --strict` (use a virtualenv;
  output goes to the git-ignored `debug/user-guide-site`).
- PR checks enforce the template in `.github/PULL_REQUEST_TEMPLATE.md`: **Check docs updated**
  (`pr-docs.yml`) fails when app sources change without a `README.md`/`docs/user-guide/` change or a
  `## Docs` reason, and **Validate release notes** (`release-notes.yml`) requires a factual
  `## Release notes` section. Both scripts live in `.github/scripts/` and can be run locally against
  a PR body before opening the PR.
- Guide screenshots live in `docs/user-guide/images/`; all but one are 1920×1080, so downscale 4K
  emulator captures to match (for example `sips -Z 1920 shot.png`).

## Baseline profile — when to regenerate

The app ships a generated baseline profile at `app/src/githubRelease/generated/baselineProfiles/`
(`baseline-prof.txt` drives ART's AOT compilation; `startup-prof.txt` drives dex layout). It is
generated on a real device by `benchmark/`, and the generated files **are committed**.

```bash
./gradlew :app:generateGithubReleaseBaselineProfile
```

**Regenerate when:**

1. Before tagging a release, if the profile has not been regenerated since the last one.
2. After changing the cold-start path — `MainActivity.onCreate`, `FlowApp`, app-level DI graph,
   theme resolution, or player/cache initialization.
3. After changing a journey the generator exercises (app launch, Home feed scroll), or after
   editing `BaselineProfileGenerator` itself.
4. After a Compose, Media3, or AGP/Kotlin upgrade that shifts which framework classes run.

**Do not regenerate** for routine feature or UI work that does not touch the above. It is a ~20 min
run that occupies a physical device, and the resulting diff is thousands of lines of churn.

**Requirements and gotchas:**

- Needs a connected physical device (`useConnectedDevices = true`). On MIUI/HyperOS, Developer
  options must have **both** "USB debugging (Security settings)" (grants `INJECT_EVENTS`) and
  "Install via USB". Pass `-PbaselineProfileEmulator=true` to use the managed Pixel 6 instead.
- Keep the device awake and connected for the whole run; a disconnect fails the task outright.
- `startup()` must **not** settle past first frame. `startActivityAndWait()` already returns
  there, and adding a `waitForIdle` sweeps the feed load into `startup-prof.txt`, which then
  asserts nearly the whole app is startup-critical and leaves R8 unable to fit the set into
  `classes.dex`. Keep `startup-prof.txt` a genuinely small subset of `baseline-prof.txt`.
- Profile size is **not** a measure of startup work: it records everything executed during the
  journey on any thread, so moving work to a background thread keeps it in the profile. Use
  `StartupBenchmark` (`:benchmark:connectedBenchmarkReleaseAndroidTest --no-configuration-cache`)
  to measure.
