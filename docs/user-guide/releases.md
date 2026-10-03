# Release notes

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

Update your existing Milkbeat installation to retain sign-ins and settings. Local and SMB music continue to work without plugins; streaming plugins are third-party downloads distributed separately from the app.

| Third-party plugin | Downloader code |
| --- | --- |
| YouTube Music and YouTube | **494** |
| Spotify metadata | **981** |
| Beatport | **393** |

Plugin capabilities depend on the installed version, account and subscription. YouTube sign-in is optional for normal playback and supports personalized Home with free accounts. Spotify supplies metadata and requires a separate audio provider. Beatport requires sign-in and a streaming subscription for full streams.

- [Install Milkbeat](getting-started.md)
- [Prepare private playlists](providers.md#prepare-private-playlists)
- [Use radio modes and the queue](playback.md#mixes)
- [All GitHub releases](https://github.com/johnneerdael/Milkbeat/releases)
