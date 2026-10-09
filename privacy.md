# Privacy and Permissions

Milkbeat has no account system of its own, no analytics or telemetry SDK, no crash reporting
service, and no advertising identifier. Nothing is uploaded to a server operated by the project.
Watch history, subscriptions, playlists, downloads, and recommendation data are stored in a local
Room database and in local DataStore preferences on the device. The local databases are exported
only when the user explicitly starts a backup export or a device-to-device sync over their own
local network.

Separately installed streaming plugins can access their provider's account library and report
individual listens to that provider. Playback reporting is on by default and can be switched off
in the signed-in plugin's details. This does not upload the local history database. Protected
playback also uses the device's platform DRM license and provisioning exchanges, described below.

Signing in with a Google account is optional; see [Google account sign-in](#google-account-sign-in).

This document lists every permission that appears in the built APK, why it is declared, when it is
requested, and whether the app still works without it.

## Summary

| Permission | Type | Feature | Required? |
| --- | --- | --- | --- |
| `INTERNET` | install-time | All network access | Yes |
| `ACCESS_NETWORK_STATE` | install-time | Offline detection, retry policy, download constraints | Yes |
| `ACCESS_WIFI_STATE` | install-time | Local IP for DLNA casting and device sync | No |
| `CHANGE_WIFI_MULTICAST_STATE` | install-time | SSDP discovery for DLNA casting | No |
| `WAKE_LOCK` | install-time | Background playback and downloads | Yes |
| `FOREGROUND_SERVICE` | install-time | Playback and download services | Yes |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | install-time | Media3 playback service (API 34+) | Yes |
| `FOREGROUND_SERVICE_DATA_SYNC` | install-time | Download and device-sync services (API 34+) | No |
| `POST_NOTIFICATIONS` | runtime | Media notification, download progress, new-video alerts | No |
| `CAMERA` | runtime | QR code scan for device-to-device sync | No |
| `RECORD_AUDIO` | runtime | Microphone song recognition | No |
| `READ_MEDIA_VIDEO` | runtime, API 33+ | Local video browser, recovering existing downloads | No |
| `READ_MEDIA_AUDIO` | runtime, API 33+ | Local music browser, recovering existing downloads | No |
| `READ_EXTERNAL_STORAGE` | runtime, API 32 and below | Local-folder browser on Android 10 and older; legacy media access | No |
| `WRITE_EXTERNAL_STORAGE` | runtime, API 28 and below | Writing downloads on older Android | No |
| `MANAGE_EXTERNAL_STORAGE` | special access | Direct local-folder browser on Android 11+; optional public downloads | No |
| `SYSTEM_ALERT_WINDOW` | special access | Fallback popup player where the ROM has no working PiP | No |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | special access | Keeping background alerts and playback alive on aggressive OEMs | No |
| `DOWNLOAD_WITHOUT_NOTIFICATION` | install-time | Legacy, no longer used, scheduled for removal | No |
| `RECEIVE_BOOT_COMPLETED` | install-time, from library | Added by `androidx.work`, reschedules background jobs after reboot | Library |
| `REQUEST_INSTALL_PACKAGES` | install-time, from library | `github` flavor only, in-app updater | Library |
| `<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | signature | Added by `androidx.core`, the app's own signature-level permission | Library |

Everything marked "No" is optional. The app degrades to the feature being unavailable rather than
refusing to start, and none of these are requested at launch.

## The four sensitive permissions

### `CAMERA`

Used by one screen: Settings > Device Sync. Milkbeat can copy a library between two of the user's own
devices over the local network. The session key is passed out of band by showing a QR code on one
device and scanning it with the other, so the key never travels over the wire. The camera preview
is bound only while that screen is open, frames are decoded locally by ZXing, and no image is
stored or transmitted.

Requested at: `app/src/main/java/io/github/aedev/flow/ui/screens/sync/SyncSetupContent.kt`

Users who never open Device Sync are never asked for it.

### `RECORD_AUDIO`

Used by one feature: song recognition, reachable from the recognition screen and its home-screen
widget. While the user holds the button, the app records a short microphone sample, computes an
audio fingerprint on device, and sends only that fingerprint to Shazam's public endpoint
(`amp.shazam.com`) to get the track title back. The raw recording is not written to disk and is not
uploaded. Recording stops as soon as the query is made or the screen is left. There is no
background or passive listening.

Requested at: `app/src/main/java/io/github/aedev/flow/ui/screens/recognition/RecognitionScreen.kt`

Gated at: `app/src/main/java/io/github/aedev/flow/data/recognition/MusicRecognitionRepository.kt`

### `READ_MEDIA_AUDIO`

Two uses, both local:

1. The local media browser (Library > Local media) plays music files already on the device, so the
   app works as an offline player with no network.
2. Recovering downloads. Milkbeat can save downloads to a public folder that survives an uninstall. On
   reinstall, the download library is rebuilt by reading those files back. Audio-only downloads are
   audio files, so recovering them needs `READ_MEDIA_AUDIO` alongside `READ_MEDIA_VIDEO`.

Nothing is scanned in the background. The `MediaStore` query runs when the user opens the local
browser or triggers a download rescan, and the results stay on the device.

Requested at: `LocalMediaScreen.kt`, `DownloadsScreen.kt`, `DownloadSettingsScreen.kt`

### `SYSTEM_ALERT_WINDOW`

Used for the fallback popup player. Android's native picture-in-picture is the default path and
needs no permission. On ROMs where PiP is missing, disabled by the vendor, or broken, Milkbeat can draw
the small floating video window itself with a `TYPE_APPLICATION_OVERLAY` window instead. The
permission is checked before that path is taken, and if it has not been granted the app stays with
native PiP or with no popup at all. It is never used to draw over other apps for any other purpose,
and no overlay exists outside an active playback session.

Checked at: `app/src/main/java/io/github/aedev/flow/player/PictureInPictureHelper.kt`,
`app/src/main/java/io/github/aedev/flow/service/VideoPlayerService.kt`

Window created at: `app/src/main/java/io/github/aedev/flow/player/PopupPlayerWindow.kt`

## Google account sign-in

### YouTube Music web session

On Android TV, Settings > Plugins > YouTube Music > Sign in lets the user sign in to their own Google
account so the YouTube Music tab and Library show that account's YouTube Music and YouTube feeds.
Without it, the tab shows the regular signed-out YouTube Music home.

- Google's own sign-in page runs on the TV in a WebView with its own isolated profile, separate from
  the storage used for playback. What the user types on the phone, the password included, is
  decrypted on the TV and inserted straight into that page; it is not stored or logged.
- The phone reaches the TV through a small web page served by the TV on the local network, only while
  the sign-in screen is open. Everything between them is encrypted (AES-256-GCM) with a key that is
  only in the QR code's URL fragment, which browsers never send over the network. The page lists the
  sign-in page's buttons so they can be tapped from the phone.
- After sign-in, only the resulting session cookie is kept, encrypted with a key in the Android
  Keystore and excluded from backups and device transfers. It is sent only to YouTube and YouTube
  Music: to read feeds (the YouTube Music home, library and history, and the YouTube watch history),
  and to play music as the account.
- Playback: while signed in, the app is the account's visitor on YouTube, and each track's YouTube
  Music player request is made with the session, as YouTube Music itself does. When a track has
  played for 30 seconds (or half of a shorter track), the play is reported to the playback-tracking
  address that request returned, so it appears in the account's history and shapes its
  recommendations. **Report plays to YouTube Music** in that plugin's details (Settings > Plugins)
  switches the reporting off. The audio itself may come from YouTube's other clients, whichever answers fastest.
- **Sign out** in that plugin's details deletes the stored session.

### YouTube Video TV-code session

The YouTube Video provider, including its stable and preview releases, uses Google's TV
device-code flow. Its QR opens YouTube's
activation address, or the user enters the displayed code on Google's device page. Google handles
the password and consent directly on the user's phone; neither passes through Milkbeat's phone
viewer. The TV polls the short-lived challenge while its pairing screen is visible.

After explicit confirmation, the plugin stores its access and refresh tokens in its own encrypted
secret storage, sealed through the Android Keystore. These credentials serve Google/YouTube account,
TV Music, Library and playback requests. The account remains separate from YouTube Music's web
session, and signing out of YouTube Video removes its stored credentials. The plugin's installation
review declares the exact Google and YouTube activation destinations.

Artwork view can download the accepted playback provider's highest available thumbnail even when
another catalog supplies the track. It preserves the original title, artist and queue identity;
loading that image does not start video streaming.

## The remaining permissions

### Network

`INTERNET` is needed to fetch video and music streams, metadata, thumbnails, and search results.

`ACCESS_NETWORK_STATE` backs the offline banner, the retry and backoff logic in the extractor, and
the "download on Wi-Fi only" constraint.

`ACCESS_WIFI_STATE` reads the device's own address on the local network. Three features need it:
DLNA casting, where the app runs a small local HTTP proxy and has to tell the TV which address to
pull the stream from, Device Sync, which puts the host's LAN address into the QR code, and phone
sign-in on the TV, which does the same for the sign-in page. It does
not scan for or list nearby networks, which on modern Android would require the location permission
that Milkbeat does not declare.

`CHANGE_WIFI_MULTICAST_STATE` holds a multicast lock while searching for DLNA and UPnP renderers.
SSDP discovery is multicast, and Android drops multicast packets without this lock. The lock is
acquired when a cast search starts and released when it ends.

### Playback and services

`WAKE_LOCK` keeps the CPU alive while audio plays with the screen off and while a download runs.
Media3 also uses it internally through `setWakeMode`. The app switches between a local and a
network wake mode depending on whether playback is in the foreground, and releases the lock when
playback stops.

`FOREGROUND_SERVICE` plus `FOREGROUND_SERVICE_MEDIA_PLAYBACK` are what Android requires from API 34
onward to run the Media3 playback service that owns the media session and the media notification.

`FOREGROUND_SERVICE_DATA_SYNC` covers the two non-playback services: the download service, so long
downloads are not killed when the app is backgrounded, and the Device Sync transfer service, so a
LAN transfer survives the screen going off. Both run only while that work is actually in progress.

`POST_NOTIFICATIONS` covers the media notification with playback controls, download progress and
completion, and optional new-video alerts for subscribed channels. Declining it leaves playback and
downloads working, without their notifications.

### Storage

`READ_MEDIA_VIDEO` mirrors `READ_MEDIA_AUDIO` above for video files: the local video browser, and
recovering video downloads after a reinstall.

`READ_EXTERNAL_STORAGE` (capped at API 32) and `WRITE_EXTERNAL_STORAGE` (capped at API 28) are the
pre-Android-13 equivalents. The `maxSdkVersion` caps in the manifest mean they are not requested on
newer releases.

`MANAGE_EXTERNAL_STORAGE` is optional and off by default. Downloads go to app-private storage
unless the user opts into a custom location in Download settings, at which point Milkbeat can write to
the public `Movies` and `Music` folders so the files survive an uninstall and are visible to other
apps. The app checks `Environment.isExternalStorageManager()` and sends the user to the system
settings page rather than assuming the grant.

For the TV's local-folder browser, access is requested only after Settings → Music folders →
**Choose local folder**. Android 10 and older request read-storage permission; Android 11 and
newer open Android's all-files access settings. Opening the app or Settings alone does not request
access. Denying it leaves other features usable. The native browser only reads selected music
folders, but Android's all-files grant permits broader read/write access to shared storage.
**Use Android folder picker** remains available for a grant limited to the chosen folder; existing
folder grants are preserved. No root privileges are used. Android 10's legacy-storage compatibility
flag allows direct paths on that version; it does not grant a permission by itself.

### Battery

`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` only lets the app show the system dialog asking the user to
exempt it from Doze. It is presented in context, when the user enables new-video notifications,
because several OEM battery managers put the app in a restricted App Standby bucket and cut its
background network access, which silently starves the periodic subscription check. Declining leaves
everything working while the app is open.

Used at: `app/src/main/java/io/github/aedev/flow/notification/BackgroundWorkPolicy.kt`

### Legacy

`DOWNLOAD_WITHOUT_NOTIFICATION` is a leftover. Milkbeat downloads through its own service and does not
enqueue anything into Android's system `DownloadManager`, so this permission has no effect. It will
be removed from the manifest.

## Permissions added by libraries

These are not declared in Milkbeat's own manifest. They are merged in from dependencies.

`RECEIVE_BOOT_COMPLETED` comes from `androidx.work:work-runtime`. WorkManager uses it to restore
scheduled jobs after a reboot. Milkbeat's jobs are the subscription check, the upcoming-video reminder,
the optional auto-backup, and, on the `github` flavor, the update check. Milkbeat registers no boot
receiver of its own.

`<package>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` comes from `androidx.core`. It is a
signature-level permission that the library defines under the app's own package name so
`ContextCompat.registerReceiver` can keep dynamically registered receivers non-exported. It grants
nothing to other apps and takes no capability from the system.

`REQUEST_INSTALL_PACKAGES` comes from `com.github.supersu-man:apkupdater-library` and is present in
the `github` flavor only, for the in-app updater that offers to install a new release APK. The
`foss` flavor does not include the updater library and does not carry this permission.

## Build flavors

Milkbeat ships two flavors. The permission difference between them is the one above.

- `foss`: no in-app updater, no `REQUEST_INSTALL_PACKAGES`. This is the build intended for F-Droid
  style distribution.
- `github`: adds the in-app updater and the optional Discord presence integration.

## Network endpoints

For completeness, these are the hosts the app can contact. All are contacted directly, with no
project-operated proxy or relay in between.

SoundCloud TV pairing opens the provider’s activation page directly in the phone’s browser. The TV
creates and polls a short-lived challenge only while its pairing screen is visible. Its session token
stays in the plugin’s encrypted secret storage. Provider protocol requests can include OS version,
device model and a random identity generated for that plugin installation, never a captured device
or advertising identifier. TV-paired playback currently does not submit listening-history reports and uses clear HLS only;
its mobile license exchange remains unverified. The protected playback path below applies to
supported providers and existing SoundCloud web sessions.

Installed plugins declare their own network hosts for review during installation. The SoundCloud
plugin uses SoundCloud metadata, playback and image services, plus its license service for protected
recordings. Media3 and platform Widevine send license challenges to the plugin's approved license
destination and may provision the device through an approved platform service such as
`www.googleapis.com`. These exchanges can carry DRM device/session data defined by the platform
and provider. License and provisioning requests use a separate transport without the media cookie
jar or cache; initial and redirected destinations are checked against the installed plugin's
current network grants. Credentials are refreshed with the bound playback descriptor.

- YouTube and Google: `www.youtube.com`, `m.youtube.com`, `music.youtube.com`, `i.ytimg.com`,
  `img.youtube.com`, `*.googlevideo.com`, `s.youtube.com`, `suggestqueries.google.com`,
  `suggestqueries-clients6.youtube.com`. Content, metadata, thumbnails, search suggestions.
- `accounts.google.com` and Google's sign-in pages: only while the user signs in on the TV.
- `api.pipepipe.dev`: remote signature helper, used only as a fallback when both local decoders
  fail on a given video.
- Lyrics providers, tried in order until one answers, and only when the user opens lyrics:
  `lrclib.net`, `lyrics.kugou.com`, `mobileservice.kugou.com`, `api-lyrics.simpmusic.org`,
  `lyrics-api.boidu.dev`, `lyrics.paxsenix.org`, the `lyricsplus` mirrors,
  `lyrics-api.binimum.org`, `amp-api.music.apple.com`, `beta.music.apple.com`.
- `amp.shazam.com`: song recognition, only on an explicit user request, and it receives an audio
  fingerprint rather than the recording.
- `sponsor.ajay.app`: SponsorBlock segments for videos, on by default and switchable off in
  settings. `dearrow-thumb.ajay.app`: DeArrow, only if the user turns it on.
- `returnyoutubedislikeapi.com`: Return YouTube Dislike, only if the user turns it on.
- `api.github.com` and `github.com`: release check and changelog, `github` flavor only.
- `discord.com`: rich presence, `github` flavor only, and only after the user links an account.
- Local network addresses: DLNA renderers on the LAN, the peer device during Device Sync, and the
  user's phone during TV sign-in.
- Music folder servers the user adds (SMB, WebDAV, SFTP or NFS), only the host and port entered in
  Settings → Music folders, and only while browsing, indexing or playing that folder. Passwords and
  SFTP private keys are sealed with the Android Keystore on the device. A password is sent only to
  that server (unencrypted for WebDAV over `http://`, which the editor warns about). An SFTP private
  key never leaves the device: only a signature made with it is sent, after the user has confirmed
  the server's key fingerprint.

## What Milkbeat does not do

- No account or user identifier of its own. The optional Google sign-in is used only to read the
  account's feeds, and its session stays on the device.
- No analytics, telemetry, crash reporting, or advertising SDK.
- No background microphone, camera, or location access. Milkbeat declares no location permission.
- No reading or uploading of contacts, call logs, SMS, or the installed app list.
- No uploading of local history databases or recommendation data to project-operated servers.
  The recommendation engine runs entirely on device. Enabled provider playback reporting sends
  individual listens/views to the provider that supplied playback; turn it off in plugin details
  to keep those reports local.
- No sharing of the device's media library. `MediaStore` results are read for display and playback
  and are not transmitted.
