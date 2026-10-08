# Per-provider music tabs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the single "metadata provider" choice with one sidebar tab per usable provider plus a Local tab.

**Architecture:** A `MusicSources` singleton derives the ordered tab list from plugin, account and folder
state. The single `music` nav destination hosts every music tab; each source gets its own keyed,
assisted-injected `MusicHomeFeedViewModel` and saveable state. Everything that read
`ProviderSelection.metadata` takes an explicit plugin id instead.

**Tech Stack:** Kotlin, Compose Material 3, Navigation Compose 2.10.0, Hilt 2.60.1 +
hilt-navigation-compose 1.4.0 (`hiltViewModel(owner, key, creationCallback)` verified with javap),
DataStore Preferences, Room raw queries (no schema change), Robolectric/JUnit.

**Spec:** `docs/superpowers/specs/2026-10-08-per-provider-music-tabs-design.md`

## Global Constraints

- Gradle tasks are flavor-prefixed: `:app:testGithubDebugUnitTest`, `:app:compileGithubDebugKotlin`, `:app:compileFossDebugKotlin`, `:app:assembleGithubDebug`.
- `./gradlew ktlintCheck` must pass for every touched Kotlin file (Spotless ratchet).
- No `Color(0x…)`, gradients, glow, coloured borders; theme tokens only. Material 3 only.
- User-facing strings only in `app/src/main/res/values/strings.xml`; never touch other locales.
- No Room schema change; no version bump; no new dependency.
- No new app-owned `getInstance()`; constructor/assisted injection.
- `stateIn(WhileSubscribed(5_000))` for UI-facing flows; one fetch per cause; work off the main thread.
- No new comments restating code; comments only for non-obvious why. Delete dead code.
- File budgets: component ≤500 lines, screen ≤400, ViewModel ≤600.
- Commits: `type(scope): description` + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. Cold start with a remembered provider whose account is not yet known: the app must open on that provider once accounts settle, not on another tab.
2. Signing out of (or disabling) the provider whose tab is shown: the shell moves to the start tab; no blank screen, no crash.
3. Switching between two provider tabs and back: each keeps its feed and scroll; no second home fetch inside `FRESH_FOR_MS`.
4. Catalog pages opened from a tab, from Search and from nested pages always carry `?provider=`; a page without one shows an error rather than another provider's content.
5. Old registry JSON containing `"metadata": "…"` still loads (ignored key) and the app shows tabs.

---

### Task 1: Music sources model, icons and strings

**Files:**
- Create: `app/src/main/java/io/github/aedev/flow/data/catalog/MusicSource.kt` (sealed `MusicSource`, `MusicTab`, `MusicTabs`)
- Create: `app/src/main/java/io/github/aedev/flow/plugin/catalog/KnownProviders.kt`
- Create: `app/src/main/java/io/github/aedev/flow/data/catalog/MusicSources.kt`
- Create: `app/src/main/res/drawable/ic_provider_spotify_mono.xml`, `ic_provider_soundcloud_mono.xml`, `ic_provider_beatport_mono.xml` (single-path 24dp vectors, `android:fillColor="@android:color/white"` so `Icon` tinting applies)
- Modify: `app/src/main/res/values/strings.xml` (`local_music_tab`)
- Test: `app/src/test/java/io/github/aedev/flow/data/catalog/MusicSourcesTest.kt`

**Interfaces (produced):**

```kotlin
sealed interface MusicSource {
    val key: String
    data class Plugin(val id: String) : MusicSource { override val key get() = "plugin:$id" }
    data object Local : MusicSource { override val key = "local" }
    companion object { fun fromKey(key: String?): MusicSource? }
}
data class MusicTab(val source: MusicSource, val label: String?, @DrawableRes val iconRes: Int?, val expired: Boolean, val surfaces: Set<MetadataSurface>)
data class MusicTabs(val tabs: List<MusicTab> = emptyList(), val settled: Boolean = false)

object KnownProviders { fun iconFor(pluginId: String): Int?; fun anonymousHome(pluginId: String): Boolean }

@Singleton class MusicSources @Inject constructor(registry: PluginRegistry, accounts: PluginAccounts, folders: MusicFolderRepository, @ApplicationContext context: Context) {
    val tabs: Flow<MusicTabs>
    val lastUsed: Flow<MusicSource?>
    suspend fun remember(source: MusicSource)
}
internal fun musicTabs(registry: PluginRegistryState, accounts: Map<String, ProviderAccount>, hasFolders: Boolean, pending: Set<String>): MusicTabs  // pure, unit-tested
```

Rules for `musicTabs` (pure): candidate = enabled plugin with `roles.metadata` containing `HOME`.
Include when account is `SignedIn`/`Expired`, or `KnownProviders.anonymousHome(id)`. Sort by
`manifest.name.lowercase()`. Append `Local` when `hasFolders`. `settled = pending.isEmpty()` where
`pending` = candidates with `signIn` methods whose account is unknown and whose refresh has not
finished. Label = manifest name; Local label null (UI uses `R.string.local_music_tab`).

`MusicSources.tabs`: `combine(registry.state, accounts.accounts, folders.folders.map { it.isNotEmpty() }, attempted)`;
for each candidate with sign-in methods and `accounts[id] == null` not yet attempted, launch
`accounts.refresh(id)` once on an app scope (`PerformanceDispatcher.networkIO`), recording the id in
`attempted` on completion (success or failure). `distinctUntilChanged()`.

`lastUsed`/`remember`: DataStore Preferences via a file-level `preferencesDataStore(name = "music_tabs")`, key `last_music_source`, storing `MusicSource.key`.

- [ ] Step 1: Write `MusicSourcesTest` for `musicTabs`: signed-in Spotify included; anonymous Spotify excluded; anonymous YouTube Music included; expired SoundCloud included with `expired = true`; disabled plugin excluded; plugin without HOME excluded; alphabetical order; Local last only with folders; `settled` false while a sign-in plugin is pending; `MusicSource.fromKey` round trip.
- [ ] Step 2: Run `./gradlew :app:testGithubDebugUnitTest --tests '*MusicSourcesTest'` — expect compile failure.
- [ ] Step 3: Implement the files above.
- [ ] Step 4: Re-run the test — expect PASS.
- [ ] Step 5: Commit `feat(music): derive per-provider music tabs`.

### Task 2: Local library search

**Files:**
- Modify: `app/src/main/java/io/github/aedev/flow/data/library/index/LibraryQueries.kt` (`LibraryFilter.text: String? = null`; `WHERE (title LIKE ? ESCAPE '\' OR artist LIKE ? … OR release title LIKE ?)` with `%`, `_`, `\` escaped)
- Modify: `app/src/main/java/io/github/aedev/flow/data/library/catalog/LocalCatalogProvider.kt` (`suspend fun search(request: SearchRequest): Result<MetadataPage>` on `diskIO`)
- Modify: `app/src/main/java/io/github/aedev/flow/data/library/catalog/LocalCatalogPages.kt` (`fun search(query, tracks, releases, artists): MetadataPage` with Songs/Albums/Artists shelves, using existing shelf builders)
- Test: extend the existing `LibraryQueries`/local catalog tests (find with `grep -rl LibraryQueries app/src/test`) with text filter + escaping cases.

- [ ] Step 1: Failing tests: `LibraryQueries.tracks(LibraryFilter(text = "50%_a"))` produces escaped LIKE args `%50\%\_a%`; blank text adds no clause; search page has the three shelves and empty shelves are omitted.
- [ ] Step 2: Run, expect FAIL. Step 3: implement. Step 4: PASS. Step 5: Commit `feat(library): search the local library`.

### Task 3: Remove the single metadata selection

**Files:**
- Modify: `plugin/registry/PluginRegistry.kt` (drop `ProviderSelection.metadata`, its `remove`/`withDefaults` handling)
- Modify: `plugin/catalog/PluginMetadataProvider.kt` (remove `selected`, `id`, `account`, `home`, `page`, `track`, `call`; keep `scoped`, `callFor`, `accountFor`; no longer implements `MetadataProvider`/`CatalogPlayback`)
- Delete: `data/catalog/CatalogRouter.kt`, `di/CatalogModule.kt`, `app/src/test/.../data/catalog/CatalogRouterTest.kt`
- Modify: `ui/screens/music/MusicHomeFeedViewModel.kt` → `@HiltViewModel(assistedFactory = Factory::class)`, `@AssistedInject constructor(@Assisted source: MusicSource?, plugins: PluginMetadataProvider, local: dagger.Lazy<LocalCatalogProvider>)`; provider = `scoped(id)` / `local.get()` (resolved on diskIO) / null → `needsPlugin = true` without fetching. Expose `val source`.
- Modify: `ui/screens/music/CatalogPageViewModel.kt` (no default provider; missing `provider` arg → error state)
- Modify: `plugin/playback/PluginRadio.kt` (no selection preference; scoped plugin, else first compatible by id space)
- Modify: `ui/tv/screens/library/TvMergedLibraryViewModel.kt` (`hiddenCopies` without `selection.metadata`)
- Modify: `ui/tv/screens/settings/TvPluginsSettingsPane.kt` (`ProviderRole` = AUDIO, VIDEO only; drop `R.string.tv_plugins_metadata` if unused)
- Modify affected tests: `MusicHomeFeedViewModelTest`, `ProviderCatalogScopeTest`, `PluginAudio*`, `LocalSongRadioTest`, `PlaylistPreloadRunnerTest`, `TvPluginsFocusTest`, androidTest files constructing `ProviderSelection(metadata = …)`.

- [ ] Step 1: Update tests to the new constructors (feed VM built with a fake `MetadataProvider` per source; radio picks compatible plugin without selection; registry JSON containing `"metadata"` decodes).
- [ ] Step 2: Run the affected tests — expect compile failure.
- [ ] Step 3: Implement.
- [ ] Step 4: `./gradlew :app:compileGithubDebugKotlin :app:testGithubDebugUnitTest` — PASS (UI call sites of the feed VM are fixed in Task 4; keep the build compiling by updating `TvMusicScreen` to take a `source` param in this task).
- [ ] Step 5: Commit `refactor(catalog): drop the single metadata provider selection`.

### Task 4: Shell, rail and per-source music tabs

**Files:**
- Create: `ui/tv/navigation/TvTab.kt` (`sealed interface TvTab { data class Music(val source: MusicSource?); data class Fixed(val destination: TvDestination) }`, `fun startTab(tabs: MusicTabs, lastUsed: MusicSource?): TvTab.Music`)
- Modify: `ui/tv/navigation/TvDestination.kt` (MUSIC stays as nav destination; `primary` = SEARCH, LIBRARY, SETTINGS)
- Modify: `ui/tv/navigation/TvBackModel.kt` (`currentTab: TvTab`, `startTab: TvTab`)
- Modify: `ui/tv/components/TvNavRail.kt` (takes `items: List<TvRailItem>`; `TvRailItem(tab, label, icon: @Composable () -> Painter/ImageVector, badged)`)
- Create: `ui/tv/screens/music/TvMusicTabsViewModel.kt` (activity-scoped: `tabs` stateIn, `lastUsed`, `select(source)` persists)
- Modify: `ui/tv/TvShell.kt`, `ui/tv/FlowTvApp.kt`, `ui/tv/navigation/TvNavHost.kt` (MUSIC route renders `TvMusicScreen(source)`; `openCatalog` from music passes `source` provider id)
- Modify: `ui/tv/screens/TvMusicScreen.kt` (`source: MusicSource?`; `SaveableStateHolder` per `source.key`; keyed assisted `hiltViewModel`; empty tab shows Add provider + Add music folder)
- Modify tests: `TvBackModelTest`, `TvDestinationTest`; add `TvTabTest` for `startTab`.

- [ ] Step 1: Failing tests for `startTab` (last used present → it; absent → first; no tabs → Music(null)) and back model with `TvTab`.
- [ ] Step 2: FAIL. Step 3: implement. Step 4: PASS + `:app:compileGithubDebugKotlin`.
- [ ] Step 5: Commit `feat(tv): one sidebar tab per music provider`.

### Task 5: Search chips per source

**Files:** `ui/tv/screens/search/TvSearchSources.kt`, `TvSearchState.kt`, `TvSearchViewModel.kt`, `TvSearchResults.kt`, `TvSearchScreen.kt`, test `TvSearchViewModelTest.kt`.

`TvSearchSource` → `sealed interface TvSearchSource { val key: String; data class Music(val source: MusicSource); data object Videos }`.
State: `results: Map<String, TvSearchResults>`, `musicSuggestions`, `videoSuggestions`. Backends resolved
per source by a `(MusicSource) -> TvSearchBackend?` function built from `PluginMetadataProvider.scoped` and
`LocalCatalogProvider.search`. Chips from `MusicSources.tabs` filtered to plugins with `SEARCH` (Local always).
Initial chip: last-used music source if it can search, else first music chip, else Videos.

- [ ] Step 1: Update/extend tests: initial source; switching chips fetches once per source/query; typeahead asks last music source + videos; results of a removed chip are dropped.
- [ ] Step 2–4: FAIL → implement → PASS.
- [ ] Step 5: Commit `feat(search): one music chip per provider`.

### Task 6: Library without the Local section, account sections per provider

**Files:** `ui/tv/screens/TvLibraryScreen.kt`, delete `ui/tv/screens/library/TvLocalLibraryViewModel.kt`, `TvLocalLibraryContent.kt`, their test; `ui/tv/screens/account/TvAccountLibraryViewModel.kt` (assisted `pluginId`), delete `TvPluginAccountViewModel.kt` if unused; `TvLibrarySavedContent.kt`; tests `TvAccountLibraryViewModelTest`.

- [ ] Step 1: Update `TvAccountLibraryViewModelTest` for a fixed plugin id.
- [ ] Step 2–4: FAIL → implement → PASS.
- [ ] Step 5: Commit `feat(library): account sections per signed-in provider`.

### Task 7: Documentation

README.md, `docs/user-guide/{index,getting-started,providers,library,playback,troubleshooting}.md`, AGENTS.md (Home ownership bullet). Validate with `mkdocs build --strict` in a venv. Commit `docs: per-provider music tabs`.

### Task 8: Verification

`./gradlew ktlintCheck :app:testGithubDebugUnitTest :app:compileFossDebugKotlin :app:assembleGithubDebug`, then install on the TV emulator and walk the Review Focus list. Run `graphify update .`.

### Task 9: PR, Codex review, merge

Per AGENTS.md: push with upstream to `origin feat/per-provider-music-tabs`, PR with template (Docs + Release notes sections), `@codex review`, poll every minute, fix findings, re-review, merge when approved.
