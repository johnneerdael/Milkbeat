# Providers and sign-in

Streaming catalogs come from optional, separately distributed **plugins**. Local and network folders are built in and need none. A plugin can offer any mix of three capabilities, shown under its name in Settings → Plugins:

| Capability | What it supplies |
| --- | --- |
| **metadata** | A music tab with Home, Search, artist, album and playlist pages, and the account's library |
| **audio** | Playable streams, including matches for tracks from another provider's catalog |
| **video** | Video search and playback, and music videos in now playing |

There is no metadata provider to choose: every installed, enabled catalog you can use gets its own [music tab](library.md#a-tab-per-music-service). You only choose the order of audio providers and which plugin supplies video.

![Settings → Plugins: Audio and Video sources, then the installed plugins with their capabilities and versions](images/settings-plugins.png)

## The available plugins

| Plugin | Code | Catalog | Audio | Video |
| --- | --- | --- | --- | --- |
| YouTube Music | **728** | Home, Search, artists, albums, playlists, library | Streams, cross-provider matching and radio | YouTube videos, channels and playlists |
| Spotify | **981** | Home, Search, artists, albums, playlists, library | **None: use another audio provider** | — |
| Beatport | **393** | Catalog, genres, charts, artists, labels, library | Full streams with a streaming subscription | — |
| SoundCloud | **089** | Discover, Stream, Search, Artists, albums, playlists, Liked Songs, history | Available full tracks, matching and stations | — |
| YouTube Video | **932** | YouTube's TV Music Home, Search, artists, albums, playlists, library | Streams, cross-provider matching and radio | YouTube videos and playlists |
| Deezer | **734** | Home, Flow, Search, artists, albums, playlists, library | Account streams and cross-provider matching | — |

Capabilities depend on the installed plugin version, the account and its subscription. YouTube Video shares the YouTube tab with YouTube Music: with both installed the tab shows YouTube Music's catalog, and YouTube Video's catalog appears, also without sign-in, when it is the only one. Sign-in with a TV code is optional. The [Preview app](preview-testing.md#youtube-video-plugin) installs the same release with code **932**.

<span id="youtube-video-preview"></span>

## YouTube Video

**YouTube Video 0.2.2** is available in stable Milkbeat with downloader code **932**. It requires
a Milkbeat build with plugin API 8; update the app before installing if your build is older. It keeps its own
account alongside YouTube Music and supplies SmartTube's regular YouTube TV Music section:
recommended music, charts, new videos and the other rows supplied for your region or account.
Signing in adds Liked Music before those rows when it has songs. An empty Liked Music library
does not prevent Home from loading. Show More includes the original row and its
continuations; playlist and mix pages retain track order.

To browse these feeds, disable YouTube Music when using YouTube Video for your catalog; the shared YouTube
tab, Search and Library then show YouTube Video's catalog. YouTube Music's installed package
and account remain independent. Choose the audio and video sources under Settings → Plugins.

In YouTube Video's details, choose **Sign in with a TV code**. Scan the QR or open the displayed
Google activation address and enter the code, then approve it in your Google account. The phone
need not share the TV's network. Keep the TV screen open until pairing completes. Cancelled,
declined or expired attempts need a fresh code. Guest Music browsing and playback need no
sign-in. After signing out or changing accounts, refresh open pages; old account or guest-session
page tokens are rejected.

YouTube Video searches regular YouTube recordings, including unofficial uploads. Audio starts
without picture; choose Video in Now Playing to show the accepted recording. Artwork uses its
highest available thumbnail, with the catalog cover as a fallback, while retaining the original
catalog title, artist and recording identity.

The paired host handles SABR through Media3. SABR streams support playback, not offline
downloads. Audio-only HLS needs an independent audio rendition; a combined audio/video stream
cannot silently fetch video bytes in audio-only mode. App and plugin packages are distributed
separately. The optional Preview app installs the released YouTube Video provider with the same code;
its retired prerelease codes stay pinned to their original packages.

## Deezer

**Deezer 0.2.1** provides Home with Flow, mixes and channels, Search, artist, album and playlist
pages, a read-only library and radio. Install it with code **734** on a Milkbeat build supporting
plugin API 9. Sign in with Deezer through the phone web viewer on the same network as the TV.
Its audio can play native Deezer tracks and accepted matches from other catalogs; stream
availability and FLAC or MP3 quality depend on the account, region and recording. It supplies
no video. Its encrypted streams cannot be downloaded for offline playback. On a Milkbeat build with
plugin API 10, Deezer 0.2.1 fetches each track in one request, so playback no longer pauses and
resumes during a song.

## Install a plugin

1. Open **Settings → Plugins** and scroll to **Add a plugin**.
2. Enter a registered three-digit code, or a plugin download URL (a `.mbplugin` file or a supported Buzzheavier file page; an address without a scheme defaults to HTTPS).
3. Select **Add a plugin**.
4. Review the plugin's author, description, capabilities and requested access, then select **Install**, **Update** for a newer version of an installed plugin, or **Reinstall** when the code or link fetches the version already installed. **Cancel** leaves everything as it was.

![Add a plugin: the code field and the Add a plugin action](images/settings-plugins-updates.png)

### Download codes

<!-- plugin-codes:user-guide -->
| Code | Plugin |
| --- | --- |
| **393** | Beatport |
| **981** | Spotify |
| **728** | YouTube Music |
| **089** | SoundCloud |
| **932** | YouTube Video |
| **734** | Deezer |
| **563** | YouTube |
<!-- /plugin-codes -->

Stable builds prefer the publisher's live catalog and fall back to the bundled one offline, so newly published codes work without an app update. The separate [Preview app](preview-testing.md) pins its codes to its own test catalog. The original codes **102** (Beatport), **772** (Spotify) and **416** (YouTube Music) still work. An unknown code reports an error; try the plugin's full URL or update Milkbeat.

<div class="mb-pair" markdown>
![Beatport's code 393 entered above Add a plugin](images/plugin-download-code.png)
![The review: name, version, author, description and capabilities, with Update and Cancel](images/plugin-update-consent.png)
</div>

Milkbeat verifies a plugin's signature before offering installation. Buzzheavier sometimes asks to check the download in a browser; Milkbeat continues once the page lets it through.

A plugin built for a newer Milkbeat cannot be installed: Milkbeat reports *needs a newer version of Milkbeat* instead of an install review. When the publisher lists that requirement, Milkbeat says so for a code before downloading anything. The GitHub build adds **Update Milkbeat**, which opens [Settings → About](settings.md#about); update the app there, then add the plugin again. The FOSS build shows only the message, so update Milkbeat through its store.

## Keep plugins up to date

| Control | What it does |
| --- | --- |
| **Update plugins automatically** | On by default. Looks for new plugin versions when Milkbeat starts and every 6 hours and installs them. An update that asks for new permissions waits for your review |
| **Update all plugins** | Checks now and installs every available update. It reads *All plugins are up to date* when there is nothing to install, and lists any update that needs your review as **Update** *plugin* |

An update must come from the same signing author and cannot be older than the installed version. When updates were installed or need review, Milkbeat shows a short notice; reviews wait in Settings → Plugins. Entering a plugin's code or link again still works too. Plugin updates are separate from [app updates](settings.md#about).

### Updates that need a newer Milkbeat

A plugin version can require a newer Milkbeat than the one installed. Milkbeat does not download that version or retry it as a failed update. Instead, **Update all plugins** reads *1 update needs a newer version of Milkbeat* and lists the plugin as **Update** *plugin* with *Update Milkbeat first*. A background check shows a notice once each time Milkbeat starts. In the GitHub build, selecting the row opens [Settings → About](settings.md#about) to update the app. After the app is updated, the next plugin check installs that version as usual. The installed plugin version keeps working in the meantime.

## Plugin details

Select an installed plugin to see its version, description and account, and to sign in or out, report plays, index playlists or remove it. Removing a plugin never removes local music sources. Account names are blurred in these captures.

=== "YouTube Music"

    ![YouTube Music details: Index playlists, version, Signed in as, Report plays, Sign out and Remove](images/plugin-details-youtube-music.png)

=== "Spotify"

    ![Spotify details: Index playlists, version, Signed in as and Sign out](images/plugin-details-spotify.png)

=== "Beatport"

    ![Beatport details: Index playlists, version, Signed in as, Report plays to Beatport, Sign out and Remove](images/plugin-details-beatport.png)

=== "SoundCloud"

    ![SoundCloud details while not signed in: Sign in with a TV code and Remove](images/plugin-details-soundcloud.png)

| Item | What it does |
| --- | --- |
| **Index playlists** | Matches the account's playlist tracks and Liked Songs against your audio providers in priority order, so playback can reuse the matches |
| **Report plays to** *provider* | Tracks you listen to for 30 seconds or more, or for half of a track shorter than a minute, are reported to that provider. Compatible API 8 music providers receive updates during playback and at pause/seek/final boundaries; older providers report when the listen ends. Unknown-length tracks require 30 seconds. Skipped sections do not count as listening |
| **Signed in as** / **Not signed in** | The account state. *Signed out: sign in again* means the provider confirmed the session expired |
| **Sign in** / **Sign out** | Starts the provider's [sign-in method](#sign-in) or forgets the session |
| **Remove** | Uninstalls the plugin and its tab |

Completed indexing matches are kept when you cancel. Index again after changing the account or the audio-provider order.

## Sign-in and account requirements

- **YouTube Music:** sign-in is optional. Signing in turns Home into a personalized feed and makes your library available. Free YouTube accounts are supported; Premium is not required for personalization.
- **YouTube Video:** sign-in is optional. Pair it with a TV code from any phone for a personalized Home and your library; signed out, it browses YouTube's TV Music feeds. YouTube Video 0.2.2 needs a Milkbeat build with plugin API 8.
- **Deezer:** sign in through the phone web viewer for your catalog, Flow and read-only library. Audio availability and quality depend on the account and recording. Deezer 0.2.1 needs a Milkbeat build with plugin API 9.
- **Spotify:** a catalog with **no audio source**. Its value is your personalized feed, playlists, Liked Songs and library after signing in. Keep at least one audio provider enabled.
- **Beatport:** sign in for the catalog and your library. Beatport's own full-length audio needs an active streaming subscription; otherwise SoundCloud or YouTube Music can match the recording. A free account does not guarantee a match.
- **SoundCloud:** sign in for personalized Home, playlists, Liked Songs, history and Artists. Public search and catalog pages can work anonymously. Full-track availability depends on the recording and your listening subscription; Artist Pro does not grant Go+ listening rights. Preview-only or restricted recordings can appear but cannot supply full-track audio, so keep another audio provider as a fallback. SoundCloud plugin 0.2.0 needs a Milkbeat build with plugin API 5. TV-paired accounts currently play clear HLS streams only; protected-only recordings cannot play with this session, and plays from a TV-paired account are not reported to SoundCloud. Existing web sessions keep their Widevine playback path where the account and device allow it.

## Sign in

Open the installed plugin's details and choose its sign-in method.

**TV code pairing (SoundCloud):** select **Sign in with a TV code**, scan the QR code or open the displayed address on a phone or computer, sign in and approve the short code. The phone does not need to be on the TV's network. Keep the TV screen open while it waits: leaving it, pressing Back or sending Milkbeat to the background cancels the attempt, and an expired or declined code can be requested again. The plugin accepts the account only after its account and Home requests succeed.

![Example TV pairing screen with a fake demonstration code](images/soundcloud-tv-pairing-example.png)

**Web sign-in (YouTube Music, Spotify, Beatport and Deezer):** scan the TV's QR code with a phone on the same network. The [phone viewer](https://github.com/johnneerdael/Milkbeat/blob/main/docs/phone-sign-in-remote-view.md) streams the provider's real sign-in page from the TV; use touch and typing on the phone to complete sign-in and any verification. Browsing and playback stay in Milkbeat's TV interface.

**Google account through MicroG-RE-TV (plugins that offer it, such as YouTube, code 563):** select **Sign in with your Google account**. Everything happens on the TV with the remote; no phone is needed.

1. If MicroG-RE-TV is not installed, the stable GitHub build offers **Install MicroG-RE-TV**. It downloads the latest release from [MicroG-RE-TV](https://github.com/johnneerdael/MicroG-RE-TV/releases) and opens Android's install prompt. The first time, Android asks you to allow Milkbeat to install apps; allow it and press Back. The stable foss build asks you to install it yourself. Milkbeat Preview and debug builds cannot use this sign-in at all, so installing MicroG-RE-TV for them does not help. A MicroG-RE-TV older than 7.2.1.5 cannot sign Milkbeat in: the stable GitHub build offers **Update MicroG-RE-TV**, which installs the latest release over it and keeps your account, and the foss build asks you to update it. Other MicroG builds under the same package are used as they are.
2. Pick your Google account from MicroG-RE-TV in the TV's account picker. On affected Google TVs, Milkbeat opens Android's standard picker to show MicroG accounts; other TVs keep their configured picker. Regular Google accounts are excluded. If there is none yet, add it in MicroG-RE-TV when Android offers to.
3. MicroG-RE-TV 7.2.1.5 and later grant Milkbeat the access the plugin needs, such as "use your account as YouTube Music", without asking. If your MicroG build asks instead, select **Allow**; declining ends the attempt, and **Retry** asks again.

On TCL TVs, which stop apps from starting other apps in the background, Milkbeat starts MicroG-RE-TV itself while it signs you in. If sign-in or playback from the plugin still fails there, allow MicroG-RE-TV to start on its own with adb: `adb shell appops set app.revanced.android.gms AUTO_START allow`.

Milkbeat keeps the account name, never a password or token. MicroG-RE-TV keeps your grants and issues short-lived tokens; to withdraw access, remove it in MicroG-RE-TV. This sign-in works only in the stable app (`nl.neerdael.milkbeat`), not in Milkbeat Preview or debug builds.

When a provider reports an expired sign-in, Milkbeat asks the plugin again in the background, retrying temporary connection failures, and restores the account without any action when it still works. With YouTube Music 0.2.4 or later, a single refused request no longer signs the account out. An account the provider confirms expired needs sign-in again; local folders keep working.

## Set audio priority

![Audio provider priority with the per-provider switches for playing other services' tracks](images/settings-audio-priority.png)

Under **Audio**, use **Move earlier** and **Move later** to order the providers, then select **Done**. Every track tries the providers in this order, including a track that comes from one of them: with YouTube above SoundCloud, a SoundCloud track is first looked up and played on YouTube, and its own SoundCloud stream is the fallback. When a provider's turn comes for one of its own tracks, it plays that track directly without a search.

Each provider's **play other services' tracks** switch decides whether it is in the order:

- **On:** the provider is in the order above and is used for every track in its turn.
- **Off:** the provider plays only its own tracks. It is tried first for those, and if it cannot play one, such as Beatport without a streaming subscription, the track continues down the order. It never searches for other services' tracks, even when one of them carries its ID. The **Audio** row in Settings → Plugins lists it after the order as *provider* (own tracks only).

Beatport starts with the switch off, and updating Milkbeat switches it off once for an existing setup; switch it on to add it to the order again. Updating a plugin keeps your choice.

Spotify has no audio of its own, so Milkbeat searches up to four providers at once. A faster match from a lower provider is used only once every higher provider has failed; the remaining searches are cancelled as soon as the result is decided.

YouTube matching searches recorded videos, including static Art Tracks and archived live sets, whichever now-playing view you use. It checks recording identity, so an unrelated performance, cover, different remix or short excerpt is rejected, and full versions are preferred over mixed excerpts. A live set of 20 minutes or more also matches a YouTube upload up to 15% longer or shorter, such as a promoter's upload with an intro, when the performer, event and year are the same. The picture loads only when you choose [the video view](playback.md#visualizer-music-video-or-artwork). With YouTube Music, recorded-video matching needs plugin 0.3.0 or later.

![Video: None or YouTube Music](images/settings-video-provider.png)

Under **Video**, choose which plugin supplies videos, or **None**.

## Spotify with YouTube audio

Sign in to Spotify and keep YouTube Music enabled under Audio. Spotify supplies the catalog and playlists; YouTube supplies a matched recording, which can differ from the catalog entry. The queue then continues with the YouTube radio of the queue's first track, matched on YouTube.
