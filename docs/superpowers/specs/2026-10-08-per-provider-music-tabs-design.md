# Per-provider music tabs — design

Date: 2026-10-08. Branch: `feat/per-provider-music-tabs`.

## Intent

Listeners should not have to pick one "metadata provider". Every metadata provider they can use gets
its own sidebar tab with a monochrome brand icon, and linked music folders get a Local tab — up to
five music screens (Beatport, SoundCloud, Spotify, YouTube Music, Local), followed by the fixed
Search, Library and Settings tabs.

Decisions taken with the owner:

| Question | Decision |
| --- | --- |
| Which providers get a tab | Enabled plugins with a metadata role and a signed-in (or expired) account. YouTube Music also gets a tab without sign-in, as soon as it is installed and enabled. |
| Icons | Monochrome vectors bundled in the app, keyed by plugin id, tinted like every rail icon. Unknown plugins get a music-note icon. |
| Music search | Search shows one chip per music tab plus Videos, and starts on the music tab last used. Only the visible chip fetches. |
| Order / start | Providers alphabetically by manifest name, Local last. The app opens on the last music tab used (persisted), falling back to the first available. |
| Library → Local section | Removed; the Local tab replaces it. |

## Out of scope

- No plugin rebuild: the manifest `icon` field stays unused.
- No Room schema change: local search uses the existing index tables through `LibraryQueries`.
- Audio priority and the video provider selection stay exactly as they are.

## 1. Tabs and navigation

### Model

- `MusicSource` (sealed): `Plugin(id: String)` and `Local`. A stable string key (`plugin:<id>`,
  `local`) is used for saved state, DataStore and ViewModel keys.
- `KnownProviders` (in `plugin/catalog/`): plugin id → `@DrawableRes` monochrome icon and
  `anonymousHome: Boolean`. Entries: `nl.neerdael.beatport`, `nl.neerdael.soundcloud`,
  `nl.neerdael.spotify`, `nl.neerdael.youtube-music` (`anonymousHome = true`). Unknown ids fall back to
  `Icons.Outlined.MusicNote` and `anonymousHome = false`.
- `MusicTab(source, label, icon)`; label is the manifest name, or `R.string.local_music_tab` for Local.
- `MusicSources` (`@Singleton`, `@Inject` constructor, in `data/catalog/`): a cold
  `Flow<List<MusicTab>>` combining `PluginRegistry.state`, `PluginAccounts.accounts` and
  `MusicFolderRepository.folders`. A plugin qualifies when it is enabled, declares `roles.metadata`
  with `MetadataSurface.HOME`, and its account is `SignedIn`/`Expired`, or `KnownProviders` marks it
  `anonymousHome`. Providers sort by manifest name (case-insensitive), then Local when at least one
  folder is configured. `distinctUntilChanged`; no polling. It also exposes the last-used source
  (DataStore Preferences key `last_music_source`) with a `remember(source)` suspend setter.
  The tab list must not trigger plugin account network refreshes beyond what `PluginAccounts`
  already does.

### Navigation

Navigation Compose 2.10 reuses one back-stack entry when only arguments differ, so a
`music/{source}` route would share one ViewModel across tabs. Instead:

- `TvDestination` keeps `MUSIC` as the single nav destination hosting all music tabs, plus `SEARCH`,
  `LIBRARY`, `SETTINGS`. Its rail entry is no longer drawn directly.
- `TvTab` (sealed): `Music(source: MusicSource?)` (null = the empty "get started" tab) and
  `Fixed(destination)`. The rail draws `MusicTab`s (or the single empty Music tab) followed by the
  fixed tabs. Shell tab history, `TvBackModel` and the start tab work on `TvTab`.
- Selecting a music tab records it as last used, then navigates to `TvDestination.MUSIC` with the
  existing `saveState`/`restoreState`/`launchSingleTop` options. The shell holds the selected
  `MusicSource` and passes it to the MUSIC destination.
- `TvMusicScreen(source)` obtains its feed ViewModel with
  `hiltViewModel<MusicHomeFeedViewModel, MusicHomeFeedViewModel.Factory>(key = source.key)` (assisted
  injection), so each source has its own ViewModel within the MUSIC entry, and wraps the content in a
  `SaveableStateHolder.SaveableStateProvider(source.key)` so scroll/focus state is per tab.
- Start tab: last-used source if it is still in the tab list, else the first tab, else the empty
  Music tab. If the selected source disappears while shown, the shell switches to the start tab.
- On a detail route the rail highlights the music tab the detail was opened from.

### Detail pages stay on their provider

`openCatalog` from a music tab navigates with `TvRoutes.catalog(entity, providerId)` where the
provider id is the plugin id, or `LocalCatalogProvider.ID` for Local; nested opens already forward
`?provider=`. Radio seeds from the home feed are encoded with `ProviderEntityReference` as
`CatalogPageViewModel.radioSeed` already does, so radio no longer needs a global selection.

## 2. Removing the single selection

- `ProviderSelection.metadata` is deleted. `PluginJson` has `ignoreUnknownKeys = true`, so persisted
  registry files that still contain it load unchanged. `PluginRegistry.remove` and `withDefaults`
  drop their metadata handling.
- `CatalogRouter` and `CatalogModule` are deleted (their only consumers are the two ViewModels below).
  `MusicHomeFeedViewModel` takes a `MetadataProvider` chosen by source:
  `PluginMetadataProvider.scoped(id)` or `LocalCatalogProvider`.
- `CatalogPageViewModel` loses its default provider and requires the `provider` route argument.
  Every caller passes it: music tabs and search pass the source, Library already passes the owning
  plugin, nested opens forward it. A route without one shows the page's error state.
- `PluginMetadataProvider`'s "selected" calls (`call`, `id`, `account`, `track`) are removed; only
  `scoped(id)`, `callFor` and `accountFor` remain.
- `TvPluginsSettingsPane`: the Metadata role row and chooser disappear; `ProviderRole` keeps AUDIO and
  VIDEO. Plugin rows still show which roles a plugin has.
- `TvPluginAccountViewModel` (account banner) takes the plugin id of the tab it is shown on instead
  of the selection.
- `PluginRadio`: the metadata plugin is the scoped seed's plugin, else the first compatible enabled
  plugin by track id space, preferring plugins that currently have a tab. With neither, it goes
  straight to audio radio as today's no-selection path does.
- `TvMergedLibraryViewModel.hiddenCopies`: hide a mirror's destination copy whenever its source
  plugin is signed in with the recorded account (drop the `== selection.metadata` condition).
- `TvLocalLibraryViewModel`, `TvLocalLibraryContent` and Library's Local section are removed, with
  their strings and tests.
- `MusicHomeFeedState.needsPlugin` and `NoMetadataPluginException` remain only for the empty
  "get started" tab, which offers **Add provider** (Settings → Plugins) and **Add music folder**
  (Settings → Music folders).

## 3. Search

- `TvSearchSource` becomes a sealed key: `Music(source: MusicSource)` and `Videos`. Per-source results,
  filters and jobs move from `EnumMap` to maps keyed by the source key.
- The chip row shows one `TvFilterChip` per music tab (same label/icon as the rail) plus Videos,
  driven by `MusicSources`. The initially shown chip is the last-used music source, else Videos.
- Only the visible chip searches; typeahead asks only the visible source. Switching chips searches
  that source once for the current query, as switching halves does today. A chip whose source
  disappears is dropped and the view falls back to the start source.
- Plugin sources search through `scoped(id)` with `PluginOperations.search`/`suggest`, offered only
  when the plugin declares `MetadataSurface.SEARCH`.
- Local search: `LocalCatalogProvider.search(request)` adds a `text` field to `LibraryFilter`
  (case-insensitive `LIKE` over track title, artist and release title; wildcards escaped) and returns a
  `MetadataPage` with Songs, Albums and Artists shelves built by `LocalCatalogPages`, opening pages
  with the Local provider id. Local typeahead returns no suggestions. Runs on `diskIO`.

## 4. Rail and icons

- `TvNavRail` takes a list of rail items (`TvTab` + label + painter/vector + badged) instead of
  iterating `TvDestination.primary`. Visual design is unchanged: same `Surface`/shape/colour tokens,
  icons tinted by `LocalContentColor`, so focused icons invert to `onPrimary`.
- Brand icons are single-path monochrome vector drawables (`ic_provider_beatport_mono`,
  `ic_provider_soundcloud_mono`, `ic_provider_spotify_mono`, existing `ic_youtube_mono`), drawn with
  `Icon(painterResource(...))` so they tint. No colour literals. Local uses `Icons.Outlined.Folder`.
- New strings in `values/strings.xml` only.

## 5. Performance

- Feeds load only when their tab is composed (existing `LaunchedEffect(viewModel) { load() }` with
  `FRESH_FOR_MS` caching). Only the shown tab is composed; no prefetch of other tabs.
- `MusicSources` is event-driven; `stateIn(WhileSubscribed(5_000))` where exposed to UI.
- Tab ViewModels live in the MUSIC entry's `ViewModelStore` for the entry's lifetime: at most one per
  source (≤5), each idle while its tab is not composed (its flows use `WhileSubscribed`).

## 6. Testing

Unit (Robolectric/JUnit, `:app:testGithubDebugUnitTest`):

- `MusicSourcesTest`: qualification rules (signed in, expired, anonymous YouTube Music, anonymous
  Spotify excluded, disabled, no HOME surface), ordering, Local presence, last-used persistence.
- `TvBackModelTest`/`TvDestinationTest` updated to `TvTab` and start-tab resolution.
- `MusicHomeFeedViewModelTest` updated for a source-scoped provider.
- `TvSearchViewModelTest`: per-source chips, initial chip, one fetch per visible source, chip removal.
- `LocalCatalogSearchTest`: text filter escaping and result shelves.
- `PluginRadio` tests updated for scoped/compatible plugin choice without a selection.
- `CatalogRouterTest`, `TvLocalLibraryViewModelTest` deleted with their subjects; `TvPluginsFocusTest`
  updated for the two remaining roles.

Build: `ktlintCheck`, `:app:compileGithubDebugKotlin`, `:app:compileFossDebugKotlin`,
`:app:assembleGithubDebug`, then run on the TV emulator: tabs appear/disappear on sign-in/out,
switching keeps each tab's scroll, Back order, search chips, Local search, detail pages stay on the
right provider.

## 7. Documentation

Update `README.md` and `docs/user-guide/` (`index.md`, `getting-started.md`, `providers.md`,
`library.md`, `playback.md`, `troubleshooting.md`) to describe per-provider tabs instead of a
selected metadata provider, and `AGENTS.md` where it describes Home ownership.
