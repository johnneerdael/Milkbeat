# Release notes

Every merged change to `main` publishes a new release with its own notes on [GitHub Releases](https://github.com/johnneerdael/Milkbeat/releases). This page summarizes the larger changes.

## Since 0.9.0 (up to 0.9.55)

### Browsing

- **A sidebar tab per music service** (0.9.55). Each catalog you can use gets its own tab with its logo: YouTube Music as soon as it is installed, the others once you are signed in. The metadata provider setting is gone. Milkbeat opens on the music tab you used last, and each tab keeps its place. [Music tabs](library.md#a-tab-per-music-service)
- **Local library** (0.9.10, its own tab since 0.9.55). Linked music folders get a library built from your tags: genres, recently added, artists, releases, playlists, labels and years, with tagged BPM. [Local library](library.md#local-library)
- **Search** shows a chip per music tab, including your local library, plus Videos. **Library** shows a chip per signed-in account with its own sections.

### Music folders

- **WebDAV, SFTP and NFS** (v3, 4.0, 4.1) sources next to SMB, with SFTP server-key confirmation (0.9.14). [Folders](folders.md)
- **Local storage with the remote**: Milkbeat's own D-pad browser for the TV's storage and USB drives, asking for storage access only when you choose a folder (0.9.33).
- **Radio for local music**: an enabled YouTube Music plugin seeds radio from the first local song while the file keeps playing (0.9.39).

### Providers and plugins

- **SoundCloud** plugin with protected audio support (0.9.40), and **TV code pairing** for sign-in from any phone (0.9.43, plugin 0.2.0 in 0.9.44). [Sign in](providers.md#sign-in)
- **Automatic plugin updates** and **Update all plugins** (0.9.9); updates asking for new permissions wait for your review. [Keep plugins up to date](providers.md#keep-plugins-up-to-date)
- Expired sign-ins are re-checked in the background at once (0.9.15), and a provider that briefly refuses requests pauses and resumes work instead of failing (0.9.18).
- Private playlist copies prepare in parallel batches with verified progress (0.9.12), show a YouTube mark when ready (0.9.20) and reopen without another pass for six hours (0.9.27).

### Playback

- **Whole songs buffer ahead**, and a failed stream resumes the same recording (0.9.16).
- **Audio output recovery**: silent output with advancing progress reconnects the same track, twice at most (0.9.49). [Buffering and recovery](playback.md#buffering-and-recovery)
- **Video-first matching** in the separate [Preview app](preview-testing.md) (0.9.48).
- The remote stays usable when a music home fails to load (0.9.25).

### Visualizer

- **Automatic native quality** from the ProjectM TV core: resolution follows frame rate and live memory up to 4K, with Native trails and beta preset moods (0.9.28). [Visualizer](visualizer.md)

## Milkbeat 0.9.0

### Private playlists and native YouTube autoplay

- Optional Spotify playlist preparation creates private YouTube Music copies. Enable **Prepare private playlists in YouTube Music** in **Settings → Plugins → Spotify**, with compatible Spotify and YouTube Music plugins enabled and signed in. The option is off by default.
- Your own playlists and Liked Songs prepare in the background; other playlists, including generated mixes, prepare when opened. Copies receive the source cover and fill one matched song at a time.
- Existing copies are reused after restarts and updated in place when Spotify changes. Track order and duplicate occurrences are preserved. Spotify's originals are not edited.
- Prepared playback gives YouTube the full copied playlist as its autoplay context, rather than seeding radio from only the first playing song. Milkbeat keeps Spotify's song titles, artists and artwork, and uses the matched YouTube IDs to exclude playlist songs from the radio continuation. Suggestions remain in the playback queue and are not written into the copy.
- Play shares ongoing preparation and can reuse a recently completed check. Initial preparation can still delay playback; saved matches and checkpoints reduce repeated work.

This feature requires playlist-preparation support in both third-party plugins (Spotify and YouTube Music 0.2.1 or later). Albums are not mirrored. With the option disabled, normal cross-provider matching and radio based on the first playing song remain available.

### Radio discovery and queue fixes

- **All**, **Familiar**, **Popular** and **Discover** controls appear when the provider supports them. They stay pinned above the queue and remain available as more radio pages load.
- Changing a mode replaces future radio suggestions while preserving the original playlist, the playing song and tracks you added yourself.
- Radio no longer repeats its seed song immediately after a mode change, and duplicate suggestions are removed.
- Repeated playlist songs retain their own playback and focus positions, including across loaded pages.
- Changing one mirroring pair preserves unrelated preparation. Expired background sign-ins update the account status so you can sign in again.

### Install and learn more

Update your existing Milkbeat installation to retain sign-ins and settings. Local and network-folder music continue to work without plugins; streaming plugins are third-party downloads distributed separately from the app.

| Third-party plugin | Downloader code |
| --- | --- |
| YouTube Music and YouTube | **494** |
| Spotify metadata | **981** |
| Beatport | **393** |

Plugin capabilities depend on the installed version, account and subscription. After an app update, Milkbeat reads each installed plugin's capabilities again, so features a plugin already declared, such as parallel playlist matching, become available without reinstalling it. YouTube sign-in is optional for normal playback and supports personalized Home with free accounts. Spotify supplies metadata and requires a separate audio provider. Beatport requires sign-in and a streaming subscription for full streams.

- [Install Milkbeat](getting-started.md)
- [Prepare private playlists](providers.md#prepare-private-playlists)
- [Use radio modes and the queue](playback.md#radio-and-its-presets)
- [All GitHub releases](https://github.com/johnneerdael/Milkbeat/releases)

## When a new build becomes available

Feature branches do not publish updates. A pull request to `main` must finish a Codex review of its
current commit and resolve review findings before the complete validation/build suites run. After
merge, the full main pipeline validates the app before publishing signed APKs. The in-app updater
then sees the new release. A queued or cancelled GitHub build does not make an update available.
