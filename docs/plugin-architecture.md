# Milkbeat plugin architecture

Status: architecture proposal, 2026-09-29. It replaces the earlier metadata plugin SDK proposal
(installed APKs, isolated processes) and keeps the block vocabulary of the
[Metadata plugin UI contract](metadata-plugin-ui-contract.md).

Current host extension (plugin API 4): `AudioStream` may include `drm` with `scheme: "WIDEVINE"`,
`licenseUrl` and optional license `headers`. Media3 owns license challenges and decryption.
License requests, redirects and provisioning use a separate transport that checks the installed
plugin's current network grants before sending credentials. Clear descriptors remain compatible.
Media and key requests share the same refreshable bound descriptor, so expired initial licenses
and later key renewals use current URLs and headers without retargeting provisioning or redirects.
Providers requiring DRM declare `api.min: 4`. HLS/DRM offline downloads are unsupported. The v1
non-goals below describe the original proposal, not this implemented extension.

## 1. What we are building

Milkbeat becomes a player with no content of its own. Everything it browses and streams comes from
**plugins**: single files in a format Milkbeat defines. A plugin is added by pasting (or scanning) a
URL to the file, and it can play one or both of two roles:

| Role | Answers | Examples |
| --- | --- | --- |
| **Metadata provider** | What exists and how it is presented: home, search, artists, albums, playlists, mixes, radio, the listener's library, sign-in | YouTube Music, Spotify, Last.fm-style catalogs |
| **Audio provider** | How to play a track: find it, resolve it to a stream, continue it as radio | YouTube Music, a self-hosted server, a store |

One plugin can be both (YouTube Music), metadata only (Spotify), or audio only (a server that has
files but no editorial content).

### Principles

1. **The core is empty but complete.** With no plugins installed, Milkbeat still runs: the Local
   library (SD card, USB, network shares) and a default, unpersonalised UI. Nothing is streamed or
   recommended until the listener adds plugins.
2. **One metadata provider, an ordered list of audio providers.** The listener picks the metadata
   provider that drives Home, Search and Library for all content. They pick an audio provider and
   optional fallbacks, tried in order for every track.
3. **Any metadata can be played by any audio provider.** A Spotify album is played by matching its
   tracks in YouTube Music. That matching is a host feature, not something each plugin reimplements.
4. **Plugins describe; the host renders and plays.** Plugins return typed data (pages, tracks,
   streams). They never ship UI code, never touch the player, and never see Android objects.
5. **No ambient authority.** A plugin can only reach the network hosts, storage and services its
   manifest declares, and the listener approves those when installing.
6. **Plugins are files, not apps.** A plugin is not an APK. It installs inside Milkbeat, updates
   inside Milkbeat, and is removed with Milkbeat.

### Non-goals for API v1

- DRM-protected streams (Widevine). Stream descriptors can carry DRM later without breaking v1.
- Byte-level stream production inside a plugin (for example YouTube SABR). v1 streams are URLs the
  host fetches: progressive, HLS or DASH.
- Plugin-defined UI. Presentation variety comes from the page vocabulary (layouts, item views).
- Video providers. Music videos are an optional stream of an audio track (section 7.3). General
  video browsing is out of scope.

## 2. Architecture at a glance

```text
┌──────────────────────────────── Milkbeat core ────────────────────────────────┐
│ TV UI: native renderer of pages · settings · sign-in · plugin manager           │
│ Library: Local provider (built in) · listens, likes, queue persistence          │
│ Playback: queue · resolution chain · Media3 player · visualizer · music video   │
│ Plugin host: registry · installer/updater · permissions · runtime · host APIs   │
└───────────────┬──────────────────────────────┬─────────────────────────────────┘
                │ typed calls (JSON over the    │ host APIs, each gated by the
                │ plugin API, async)            │ plugin's granted permissions
      ┌─────────▼─────────┐          ┌─────────▼─────────┐
      │ youtube-music      │          │ spotify            │
      │ .mbplugin          │          │ .mbplugin          │
      │ metadata + audio   │          │ metadata           │
      └────────────────────┘          └────────────────────┘
```

The core never calls a provider's own API. It calls the **plugin API**: a small set of typed
operations (section 5). Each plugin runs in its own sandboxed script context (section 4) and reaches
the outside world only through **host APIs** (section 4.3).

## 3. The plugin file

### 3.1 Format

- **Extension** `.mbplugin`. **Media type** `application/vnd.milkbeat.plugin+zip`.
- **Container:** a ZIP archive. Its layout:

```text
youtube-music.mbplugin
├── manifest.json        identity, version, roles, capabilities, permissions, settings, sign-in
├── plugin.js            the code: one bundled ES2020 module (the SDK's build produces it)
├── icon.png             512×512, shown in the plugin manager and on attribution chips
├── assets/…             optional read-only data the plugin loads through the host (e.g. a solver)
├── LICENSE
└── META-INF/
    ├── CONTENTS         SHA-256 of every other entry
    └── SIGNATURE        ECDSA P-256 signature over CONTENTS, plus the author's public key
```

- **Signing:** the host verifies the signature with the platform's `java.security` provider
  (ECDSA P-256 / SHA-256 exists on every supported Android version). The key pair belongs to the
  plugin's author.
- **Identity:** a plugin's identity is `id` plus its author key. An update must carry the same id,
  be signed by the same key, and have a higher `versionCode`. That is the same rule Android applies
  to APKs.

### 3.2 Manifest

```jsonc
{
  "format": 1,                              // container format
  "api": { "min": 1, "target": 1 },         // plugin API versions it runs against
  "id": "dev.milkbeat.youtube-music",
  "name": "YouTube Music",
  "version": "1.4.0",
  "versionCode": 14,
  "author": { "name": "…", "url": "…" },
  "updateUrl": "https://example.org/plugins/youtube-music.mbplugin",  // stable link, see 3.4
  "entry": "plugin.js",

  "roles": {
    "metadata": {
      "surfaces": ["home", "search", "suggest", "entity", "library", "radio"],
      "entities": ["track", "album", "artist", "playlist", "mix", "radio", "profile", "musicVideo"],
      "actions": ["like", "follow", "savePlaylist", "reportPlayback"],
      "idSpace": "ytm"                        // namespace of its ids, see 6.1
    },
    "audio": {
      "idSpaces": ["ytm"],                    // ids it can resolve directly
      "match": true,                          // can find a track from a description
      "matchCollection": true,                // can map a whole album in one call
      "radio": true,                          // can continue from a seed
      "musicVideo": true,                     // can resolve a picture stream for a track
      "protocols": ["progressive", "hls"]
    }
  },

  "signIn": [
    { "id": "google", "kind": "phoneForm", "required": false, "label": "Sign in with Google" }
  ],

  "permissions": {
    "network": ["music.youtube.com", "*.youtube.com", "*.googlevideo.com", "*.ytimg.com"],
    "browser": ["music.youtube.com", "www.youtube.com"],    // hidden web view, see 4.3
    "storage": 5242880                                       // bytes of key/value storage
  },

  "settings": [
    { "key": "region", "type": "choice", "label": "Content region", "options": "dynamic" },
    { "key": "hideVideos", "type": "toggle", "label": "Hide music videos", "default": false }
  ]
}
```

- **Unknown fields are ignored.** A higher `format` or `api.min` than the host supports blocks
  installation with a clear "update Milkbeat" message.
- **Labels in the manifest are English strings plus optional translations** (`"label@nl": "…"`).
  The host picks the locale; plugins never receive Android resources.

### 3.3 Installing

**Add plugin** (Settings > Plugins) accepts a URL in three ways, because typing on a remote is slow:

1. **From the phone:** Milkbeat shows a QR code. The phone opens a small page served by the TV on
   the local network, which is the same mechanism as today's *Sign in with phone*, and the URL is
   pasted there.
2. **Typed** with the on-screen keyboard.
3. **Opened:** a `milkbeat://add-plugin?url=…` link, or a `.mbplugin` file on USB or SD storage.

Install steps:

1. Download the file.
2. Verify the ZIP, `CONTENTS` and `SIGNATURE`.
3. Parse the manifest and check API compatibility.
4. Show a **consent screen** with the plugin's roles, network hosts, whether it may use a browser,
   and its storage.
5. Store the plugin in app-private storage.
6. Offer it as the metadata or audio provider, according to its roles.

A plugin that fails any step is not stored.

### 3.4 Updating

- **Every plugin carries an `updateUrl`.** The host reads its version the way the app's own updater
  reads the stable APK link: from where the link redirects, or from a small `version.json` next to
  the file.
- **When:** plugins are checked at app start and every 6 hours while the app is open, together with
  the app update check.
- **Install rule:** a newer `versionCode` from the same author key installs silently.
- **More permissions need consent:** if the new version asks for additional permissions, it waits
  until the listener approves them.
- **A broken update never strands the listener.** The previous version is kept until the new one has
  started successfully once.

### 3.5 Third-party plugin distribution

- **Nothing is bundled.** The APK contains no plugin, and a fresh install has only the Local library.
- Third-party plugin packages are distributed separately from the app.
- Milkbeat releases contain APKs and checksums. The README lists optional third-party downloader codes.
- Plugins use the generic installer, signature verification, permission review and update APIs.
- Stable downloader codes prefer the live publication catalog. The separate Nightly/Preview app loads `plugin-preview-download-catalog.json` from its variant assets and resolves codes without consulting stable publication; main publication metadata and assets remain stable.

## 4. The runtime

### 4.1 Language and engine

**Plugins are JavaScript.** The SDK lets authors write TypeScript and bundles it to one ES2020 file.
JavaScript is the common language of this kind of plugin (scrapers, API clients). YouTube's own
signature and n-parameter code is JavaScript, so a YouTube plugin can run its solver inside itself
rather than needing a special host feature.

**Engine: QuickJS** through `quickjs-kt`, confirmed by the phase 0 measurements on the AM6
([spike results](research/plugin-runtime-spike-2026-09-29.md)). The host still talks to it through one Kotlin interface
(`PluginRuntime`), so the engine stays replaceable. Why QuickJS over Rhino, which is on the
classpath today:

- **Language.**
  - Rhino 1.8.1, run interpreted as Android requires, rejects `async`/`await`, `class`, array spread
    and object rest. That was checked against the jar the app ships.
  - Every plugin is asynchronous (each host call returns a Promise), so on Rhino all plugin code
    would need transpiling down to old JavaScript. That makes bundles larger, slower and harder to
    debug.
  - QuickJS runs ES2020 and newer natively.
- **Speed.**
  - Rhino on Android can only interpret: ART cannot load the bytecode Rhino would generate.
  - QuickJS compiles to its own bytecode and runs natively, `JSON.parse` included.
  - Plugins spend their time parsing large JSON responses and running YouTube's solver, which parses
    the multi-megabyte player script. That is exactly where an interpreter on the JVM hurts.
- **Memory safety for the player.**
  - Rhino allocates plugin objects on the app's own Java heap and has no memory cap. A runaway plugin
    means garbage-collection pauses and, at worst, an out-of-memory crash in the process that plays
    the music.
  - Each QuickJS runtime has its own heap with a hard memory limit and an interrupt handler for
    CPU time. Tearing it down frees its memory at once.
- **The "already there" argument disappears anyway.** Rhino is only on the classpath through
  NewPipe, which leaves the core in phase 4.
- **Costs:**
  - A native library, roughly 1 MB per ABI.
  - A crash inside native code takes the app down, where a Rhino exception would not.
  - A dependency whose maintenance has to be watched.

  The first two are why the memory and interrupt limits are mandatory, and why phase 0 has to show
  the binding is stable on the AM6 (armeabi-v7a).

Rejected:
- **Rhino:** the reasons above.
- **Android's `JavaScriptSandbox`:** depends on the installed WebView supporting isolates, which
  older TV boxes do not guarantee.
- **WebAssembly with a pure-JVM interpreter:** too slow for JSON-heavy work on ART, and harder for
  plugin authors.

### 4.2 Lifecycle

- **Lazy start:** a context is created on the first call to a plugin, not at app start.
- **Idle stop:** a context idle for 5 minutes is torn down. The audio plugins of the current queue
  stay warm while music plays.
- **Every call has limits:** a wall-clock timeout (default 20 s, 60 s for sign-in steps), an
  instruction or interrupt budget, and a memory cap where the engine supports it.
- **Failure handling:** an exception or limit breach fails that call only, as a typed error
  (section 5.4). Five failures in a row disable the plugin until the next app start, and the
  plugin manager says so.
- **Warm-up:** after a plugin starts, the host calls its optional `warmUp()` at low priority, with a
  longer time budget (up to 2 minutes). This is where one-off work goes, such as preprocessing a new
  YouTube player (13.5 s on the AM6). Until it finishes, the plugin answers with what does not need
  it, for example stream clients that do not need the solver.
- **Plugin thread:** each context runs on its own thread with a deep stack (16 MB in the spike).
- **Concurrency:** at most 4 calls in flight per plugin. Host HTTP runs on the app's shared network
  pool (AGENTS.md rule 10).

### 4.3 Host APIs

The plugin sees a single global `mb`. Every call returns a Promise. What each object can do is
bounded by the manifest's `permissions`:

| API | Does | Bounded by |
| --- | --- | --- |
| `mb.http.fetch(request)` | HTTP through the app's OkHttp: connection pool, HTTP cache, TLS | `permissions.network` host allowlist; a per-plugin cookie jar; size and time limits |
| `mb.storage` | Key/value storage for the plugin's own state and caches | `permissions.storage` quota; cleared when the plugin is removed |
| `mb.secrets` | Tokens and cookies, encrypted with a key in Android Keystore | The plugin's own secrets only; never written to logs or backups |
| `mb.browser.session(url)` | A hidden WebView: load, run a script, read cookies for allowed hosts. For web sign-in and attestation (YouTube's PoToken) | `permissions.browser` host list; one session at a time; torn down after use |
| `mb.crypto` | SHA, HMAC, AES-GCM, random bytes, base64 through the platform's JCA | Nothing to bound; plugins must not hand-roll crypto |
| `mb.assets.read(path)` | Read-only files from the plugin's `assets/` | The plugin's own package |
| `mb.code.load(key, source)` | Evaluates a script the plugin derived at run time (e.g. YouTube's prepared player) and keeps its compiled bytecode, so the next start loads it instead of parsing it again (1.6 s became 72 ms on the AM6) | Bytecode lives in the app's cache under a size budget, is evicted first when space runs low, and is dropped when `key` or the plugin version changes |
| `mb.env` | Locale, region, API version, a coarse device class (TV/phone), app version | Read only; no device identifiers |
| `mb.log` | Structured logging, visible in developer mode | Rate limited; secrets redacted |

There is no file system, no Android API and no access to other plugins.

## 5. The plugin API (v1)

### 5.1 Shape

A plugin's module exports one object per role. The SDK provides the TypeScript types:

```ts
import { definePlugin } from "@milkbeat/plugin-sdk";

export default definePlugin({
  metadata: {
    async home({ filter, cursor, account }) { /* → Page */ },
    async search({ query, filter, cursor }) { /* → Page */ },
    async suggest({ query }) { /* → Suggestion[] */ },
    async entity({ ref, cursor }) { /* → Page: artist, album, playlist, mix, radio, profile */ },
    async tracks({ ref, cursor }) { /* → TrackList: every playable track of a collection */ },
    async radio({ seed, cursor }) { /* → TrackList: the continuation of a track or collection */ },
    async library({ section, cursor }) { /* → Page: liked, playlists, albums, artists, history */ },
    async act({ action, ref, value }) { /* like, follow, savePlaylist, reportPlayback */ },
  },
  audio: {
    async resolve({ id, quality, video }) { /* → Stream */ },
    async match({ track }) { /* → Candidate[] with confidence */ },
    async matchCollection({ collection, tracks }) { /* → Map<trackIndex, Candidate> */ },
    async radio({ seed, cursor }) { /* → TrackList */ },
  },
  lifecycle: {
    async warmUp() { /* optional one-off work after start, e.g. preparing the solver */ },
  },
  signIn: {
    async begin({ method }) { /* → SignInStep */ },
    async continue({ method, input }) { /* → SignInStep | SignedIn */ },
    async account() { /* → Anonymous | SignedIn{ key, name, avatar } | Expired */ },
    async signOut() {},
  },
  settings: {
    async options({ key }) { /* dynamic choices, e.g. regions */ },
  },
});
```

- **Optional operations** are declared in the manifest's `roles` and omitted from the code when not
  offered.
- **The host only calls what is declared.** It hides the UI for anything missing: no Library tab,
  no Like button, no Radio button.

### 5.2 Data model

The model is defined once, in Kotlin (`plugin-api` module, `kotlinx.serialization`). A JSON Schema
is generated from it, and the SDK's TypeScript types are generated from that schema. There is one
source of truth and no hand-maintained copies.

- **`Ref`**: `{ plugin, kind, id }`.
  - `kind`: `track`, `musicVideo`, `album`, `artist`, `playlist`, `mix`, `radio`, `profile`.
  - `mix` is a generated collection that can change (daily mixes, "My Supermix").
  - `radio` is an endless station seeded from something.
- **`TrackDescriptor`**: what any provider needs to recognise a track.
  - Fields: `title`, `artists[]`, `album`, `durationMs`, `isrc?`, `explicit`, `artwork[]`,
    `trackNumber?`, `discNumber?`, `year?`.
  - `ids`: known ids in any namespace, e.g. `{ "ytm": "…", "spotify": "…", "isrc": "…",
    "musicbrainz": "…" }`.
- **`TrackList`**: `{ tracks: TrackDescriptor[], next?: cursor, source: Ref }`, the input to the
  queue.
- **`Page`**: the existing catalog page contract, `MetadataPage` with its blocks, extended by what
  the UI contract already names:
  - `EntityHeader`, `CollectionBlock` with its `CollectionLayout` and `ItemView`, chips, filters,
    attribution.
  - Items carry a `Ref`, and playable items also carry a `TrackDescriptor`.
- **`Stream`**: `{ protocol: progressive|hls|dash, url, headers, mimeType, codecs, bitrate,
  sampleRate?, loudnessDb?, expiresAt?, cacheKey, video?: Stream }`.
  - `cacheKey` lets the host's download and cache layer reuse bytes across URL refreshes.
  - `loudnessDb` feeds volume normalisation.
- **`SignInStep`**: what the host shows next (section 8).

### 5.3 Paging and caching

- **Cursors are opaque strings** from the plugin. The host keeps the rest of a page flowing with
  the existing continuation handling (`CatalogBlockMerge`).
- **Cache lifetimes:** responses may carry `cache: { ttlSeconds, scope: account|global }`. The host
  caches pages and track lists for that long, keyed by plugin, request and account key. A sign-in
  or a region change invalidates the account scope (AGENTS.md rule 12).

### 5.4 Errors

A plugin throws `mb.error(code, message)`. The host reacts by code:

| Code | Host reaction |
| --- | --- |
| `notFound` | Skip, or show an empty state |
| `unavailable` (region, removed) | Try the next audio provider or candidate |
| `signInRequired` / `signInExpired` | Mark the account expired and offer sign-in |
| `rateLimited` (+ `retryAfter`) | Back off; do not retry before `retryAfter` |
| `network` / `timeout` | Interactive calls: retry once, then surface |
| `unsupported` | Hide the feature for this plugin |
| `internal` | Count toward the failure limit (4.2) |

Background runs (playlist preparation and library indexing) treat `network`, `timeout` and `rateLimited` as transient. They pause and retry the same step up to three times, waiting 5, 15 and 45 s or the plugin's longer `retryAfter`, continue from saved progress, and only then surface the failure to the worker's own backoff. `unavailable` is never retried this way: it means "try another provider", not "try again later".

Music Home also retries these transient failures up to three times on that schedule when page one has no content to display. It shows the failure and a Retry action immediately. After each backoff, the next automatic request waits for the Music route's lifecycle-aware state collector; leaving the route or backgrounding the app suspends pending retries until Music is shown again. A forced reload replaces the pending sequence. This gate belongs only to Home catalog fetching and does not suspend playback or queue preparation. A failed refresh with existing blocks keeps them without automatic retries.

## 6. Identity and cross-provider playback

This is the core of the design: **a metadata provider asks for an album, and the audio provider
plays it.**

### 6.1 Id spaces

- **Every id is scoped.** A plugin's ids live in the `idSpace` its manifest declares (`ytm`,
  `spotify`).
- **Shared spaces:** `isrc` and `musicbrainz` are shared, well-known spaces that any plugin may emit
  or accept.
- **Direct resolve:** an audio provider lists the spaces it resolves directly (`audio.idSpaces`).
  When a track already carries an id in one of them, no matching is needed.

### 6.2 The resolution chain

Playing anything, whether a track, an album, a playlist, a mix or a radio, goes through one path:

```text
Play album (metadata M)
  1. M.tracks(album)                       → TrackList of descriptors, paged
  2. queue ← descriptors (the queue holds descriptors, not provider ids)
  3. for the current track and the next one (gapless), resolve:
       for each audio provider A in the user's order:
         a. id in one of A.idSpaces?        → A.resolve(id)
         b. match cache hit for (track, A)?  → A.resolve(cached id)
         c. A.match(descriptor)            → best candidate ≥ threshold → cache → A.resolve
         d. unavailable / no confident match → next A
       none left → skip the track and show a playback warning (the existing snackbar)
  4. queue end → M.radio(seed = album) if declared, else A.radio(seed), else stop
```

- **Matching strategy:** audio providers declaring `musicVideo` receive `VIDEOS` requests in ordinary playback, queue preparation, preloading and playlist mirroring. The YouTube provider uses the regular site's recorded-video filter, including Art Tracks and archived live sets. Other audio providers keep Songs matching. Playlist batches stay bounded to sixteen ordered slots.
- **Confidence:** `TrackMatchScore` checks title, credited performer, recording variants and duration. A shared ISRC is authoritative. Ordinary title and artist evidence require 0.88 and 0.92 similarity; known shorter excerpts beyond ten seconds are rejected, while a longer recording can retain the existing exact-identity rule. Video title credits may identify a performer when the upload channel is a label. Long live sets additionally need explicit matching performance/event/year evidence and near-equal known durations; returned metadata is never rewritten.
- **Match cache:** successful candidates are shared by recording fingerprint and provider. Misses are scoped by matching policy and requested strategy and last one day; Songs misses cannot suppress a Videos search. Concurrent single/batch callers share the same strategy-scoped in-flight lookup. This change does not alter the Room schema.
- **Resolve lazily:** only the current and the next track, as today. Each resolve is deduped by
  queue position and descriptor fingerprint (AGENTS.md rule 8).
- **Expiry:** a stream whose URL expires is refreshed by calling `resolve` again with the same
  cache key. This keeps today's `ResolvingDataSource` refresh behaviour.

### 6.3 Radio, mixes and "similar content"

- **Radio comes from the metadata provider first**, because that is where taste lives. Today's
  YouTube behaviour (a song continues with its RDAMVM mix; an album or playlist continues with its
  automix) becomes the YouTube plugin's `metadata.radio`.
- **Audio-provider radio is the fallback.** A metadata-only plugin without `radio` falls back to
  the audio provider's `radio`, seeded with the descriptor.
- **Mixes are ordinary collections:** `entity(mix)` for the page and `tracks(mix)` to play.

### 6.4 The queue and persistence

- **The queue model:** the queue stores `QueueItem { descriptor, sourceRef, resolved?: { plugin,
  id } }`. It replaces today's `MusicTrack.videoId`-keyed model.
- **Persistence:** saved queues keep descriptors, so a restored queue still plays after the audio
  provider changes.
- **Library entries** (listens, likes, local playlists) store descriptors plus the `Ref` they came
  from.

## 7. Selection and fallback

### 7.1 Settings > Plugins

- **Metadata provider:** one of the installed metadata plugins, or *None* (Local library only).
  It drives Home, Search, Library and every entity page.
- **Audio providers:** an ordered list. The first is used, and the rest are fallbacks tried in
  order. Each entry can be switched off without uninstalling.
- **Per plugin:** its version, update status, permissions, sign-in, settings (from the manifest),
  "Check for update", "Remove".

### 7.2 Combined plugins

- **Pairing is not automatic:** a plugin that is both metadata and audio (YouTube Music) is offered
  in both lists independently.
- **Direct ids:** when the same plugin holds both roles, 6.2a always applies, so no matching
  happens.

### 7.3 Music videos

- **Optional stream:** a music video is an optional `video` stream of a track, resolved when the
  listener has video on (today's Video/Visualizer switch).
- **Any audio provider:** it asks for it by passing `video: true` to `resolve`.
- **Where it applies:** accepted sources from providers declaring `musicVideo` offer a Video choice for the original playback ID, including static Art Tracks. Eligibility is event-driven from accepted resolution and current account/provider context; original catalog IDs and metadata remain intact. Discovery never reloads playback. Explicit view selection requests picture using the accepted recording and preserves position; unavailable picture uses the existing audio recovery. Providers without `musicVideo` keep the visualizer or artwork.

## 8. Sign-in

Sign-in is plugin-defined but host-rendered, so every provider signs in the same TV-friendly way.
The manifest lists the methods; `signIn.begin` and `signIn.continue` drive them one step at a time:

| Kind | TV shows | Plugin does |
| --- | --- | --- |
| `deviceCode` | Code, QR code of the verification URL, a spinner while it waits | OAuth device flow through `mb.http`, polling at the interval the provider asks |
| `phoneForm` | QR code to a page served by the TV on the LAN; the phone shows the fields the plugin declared | Receives the fields (email/password, a pasted cookie or token) in `continue` |
| `webLogin` | A web view on the TV, or the phone form as an alternative | Uses `mb.browser` to load the provider's login page; declares the success URL and the cookies to keep |
| `token` | A single text field (also reachable through the phone form) | Validates an API key or personal token |

- **Where credentials live:** they only ever reach `mb.secrets`.
- **Account state:** `account()` reports it as `Anonymous`, `SignedIn(key)` or `Expired`, which is
  today's `ProviderAccount`. A change of `key` invalidates that plugin's account-scoped caches.

## 9. The core without plugins

### 9.1 Local provider

- **Built in, same interfaces:** the Local provider is a Kotlin plugin inside the core, implementing
  the same metadata and audio interfaces as script plugins, so the UI has no special case.
- **Sources:**
  - Android's media library and storage access for SD cards and USB drives.
  - SMB shares through a maintained SMB client. That is a new dependency, and its stated reason is
    that nothing on the classpath speaks SMB.
  - NFS comes later, once a maintained client is found.
- **Music and video:** the Local provider indexes video files as well as audio. Local videos play
  in the existing Media3 video player.
- **Browsing:** artists, albums and tracks come from file tags. Folders are available as a fallback
  view.
- **Local is always there:** it is an audio provider for local files, and appears as its own tab in
  Library whatever the chosen metadata provider is.
- **The Videos tab stays, for local videos:** movies, concerts and clips from SD, USB or SMB, with
  folders, recently added and resume position. A plugin `video` role (streamed video catalogs) can
  fill the same tab in a later API version; v1 does not define it.

### 9.2 The empty state

- **With neither a metadata plugin nor a local source,** Home shows two actions: *Add a plugin*
  and *Add a music folder*.
- **With a local source only,** Home shows the Local library: recently added, albums, artists.
  There are no recommendations and no personalisation.

## 10. Delivery plan

Each phase ships on its own and keeps the app working.

| Phase | Delivers | Proves |
| --- | --- | --- |
| **0. Spike** (engine done, [results](research/plugin-runtime-spike-2026-09-29.md)) | The QuickJS binding on the AM6 and AM9: map a recorded YouTube home response and run the n-parameter solver; measure CPU time, memory, start-up and stability. JSON Schema and TypeScript generation from the Kotlin model. | The engine holds up on the slowest device, with numbers (AGENTS.md rule 14) |
| **1. Contracts in the core** | A `plugin-api` module (model, errors, roles). `PluginHost` with a **Kotlin plugin adapter**: today's YouTube code wrapped as an in-process plugin behind the same interfaces. The descriptor-based queue and resolution chain. | The UI and player no longer reference YouTube, and a YouTube-less build runs |
| **2. Local provider** | SD card/USB through the media library, then SMB; local music and local videos (Videos tab); the empty state | Milkbeat works with no streaming at all |
| **3. Script plugins** | The `.mbplugin` format, installer, signature check, consent screen, runtime, host APIs, Settings > Plugins, updates, developer mode | Third-party plugins install and run |
| **4. YouTube Music as a file** | An optional third-party YouTube plugin: metadata, radio, audio, sign-in. The solver runs inside the plugin, and PoToken goes through `mb.browser`. The package is installed through a downloader code or URL. The Kotlin YouTube code leaves the core. | The core ships no provider code |
| **5. Spotify metadata** | A metadata-only plugin, the match cache (the approved Room change), `matchCollection` in the YouTube plugin | Cross-provider playback |
| **6. Ecosystem** | A plugin index format (a JSON list of plugin URLs) that can be added like a plugin; SDK and CLI published | Discovery beyond pasted URLs |

**Developer tooling**, from phase 3:
- `@milkbeat/plugin-sdk` provides the types, `definePlugin` and fixtures.
- The `mbplugin` CLI provides `init`, `build`, `validate`, `pack`, `sign` and `serve`.
- `serve` hosts the file on the LAN with live reload. In developer mode, the TV installs from that
  URL, reloads on change, and streams `mb.log` output to the CLI.
- Contract tests run a plugin against recorded responses, with no account and no device.

## 11. Decisions

Settled with the owner on 2026-09-29:

1. **Nothing is bundled.** Optional third-party plugins are installed separately through downloader codes or URLs (3.5).
2. **The engine is QuickJS** through `quickjs-kt`, for the reasons in 4.1, confirmed on the AM6 by
   phase 0.
3. **The format is `.mbplugin`.**
4. **Network shares start with SMB.** NFS follows when a maintained client exists.
5. **The Videos tab stays,** for local video files through the Local provider (9.1). Streamed video
   is a later plugin role.

Still open:

- **Plugin signing trust:** any author key (trust on first install, as Android does), or an optional
  trusted publisher signature that marks verified plugins.
- **Room changes:** the match cache is approved for the Spotify phase. The plugin registry and
  descriptor-based library entries still need explicit approval, and each needs a migration.

## Device-code sign-in (API 5)

A manifest can declare `{ "type": "deviceCode", "id": "tv", "label": "Pair TV" }`.
The host calls `signIn.begin({method})` and displays its opaque session handle, public user code,
verification URI, optional complete URI, cadence and expiry. Both verification destinations must
match the enabled plugin’s current browser grants. `signIn.poll({session})` answers pending,
signedIn with an account, expired or denied. A pending response may increase the poll interval.
A signedIn poll describes a validated candidate only: it does not change the provider’s current
account. After checking that the attempt remains visible, unexpired and granted, the host accepts
it through `signIn.confirm({session})`; only its validated signed-in response enters `PluginAccounts`.
Once confirmation is dispatched, it is terminal acceptance and finishes even if navigation pauses
the screen. Failed confirmations still cancel the uncommitted candidate and offer retry. `signIn.cancel({session})` forgets an unaccepted pending attempt; provider secrets remain in host secrets.

The controller runs only while its screen is resumed; its loading indicator is also removed while
paused so a retained screen performs no continuous animation. It cancels on pause/disposal and ignores stale
results after retry/cancellation. It validates the method and grants before and after polls.
The plugin validates provider access before answering signedIn; the host then invalidates account-bound
catalogs through the existing `PluginAccounts` flow. Existing web sign-in methods are unchanged.

`crypto.randomBytes` uses `SecureRandom` with a request bounded to 1–256 bytes. It exposes no system
identifier. A provider can store its generated installation identity in its own secret namespace.
`env.get` optionally includes OS version and device model for provider protocol headers.
