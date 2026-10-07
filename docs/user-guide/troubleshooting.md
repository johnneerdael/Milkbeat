# Troubleshooting

[User guide](index.md)

## Music Home is empty

For a streaming Home feed, select a metadata plugin in Settings → Plugins and complete sign-in
if its feed requires an account. With no metadata provider selected, Home uses the tagged local
library. Local music is also available in Library → Local library and Library → Folders without
a metadata plugin. When the provider's Home has nothing to show, select **Retry** to ask for it again.

## Music Home says Couldn't load content

The message below the title is the provider's reason. A temporary failure, such as a dropped connection, a rate limit or a one-off server error, is retried automatically up to three times over about a minute, or later if the provider asks to wait. Select **Retry** to try again at once; the button reads **Trying again…** while it does. Other failures, such as a missing sign-in, are not retried automatically: press Left to reach the navigation rail and open Settings → Plugins.

Automatic Home retries wait while you are on another screen or the app is in the background, then resume when you return to Music. This only pauses requests for the Home feed; music playback and queue preparation continue.

## An SMB test fails

1. Check the server IP or hostname, share name and port. Enter the server without `smb://`.
2. Confirm the TV can reach the server and that it supports SMB 2/3.
3. Check the optional folder path is inside the selected share.
4. Use a server account with read access. Guest access works only if the server grants it.
5. Use Show password to check newly entered text, then test again.
6. After access is confirmed, select **Save** before leaving the editor.

A successful network connection does not imply permission to read the share. Test access checks directory access too.

## A WebDAV test fails

1. Enter the full folder address including `http://` or `https://`, and leave the username and password out of the URL.
2. Open the same address in a browser on another device to confirm the folder exists and your account can read it.
3. A server with a self-signed certificate or Digest-only sign-in is not supported; use a trusted certificate or Basic authentication.

## An SFTP test fails

1. Check the server, port and username, and that the account may use SFTP.
2. With key sign-in, choose the key file again and enter its passphrase. Keys in the older `BEGIN EC PRIVATE KEY` format must be converted first, for example with `ssh-keygen -p -f <key>`, which rewrites them in OpenSSH format.
3. If Milkbeat reports that the server key has changed and you did not replace the server, do not continue: someone may be intercepting the connection. Otherwise select **Forget server key** and confirm the new fingerprint.
4. Check the folder path: start it with `/` for an absolute path.

## An NFS test fails

1. If Milkbeat says the server only accepts privileged ports, allow non-privileged ports on the export (`insecure` on Linux, the matching checkbox on Synology and QNAP).
2. Check the export path. NFSv4 servers can publish exports under a different path than NFSv3; try `/`, or set the NFS version explicitly.
3. Make sure the export allows the TV's IP address.
4. If folders list but files cannot be read, set a User ID and Group ID that can read the files.

## A USB folder is missing

Check that Android has mounted the drive, then select **Refresh** in Milkbeat's local-folder browser. If access was denied or revoked, choose **Choose local folder** or **Storage permissions** in Settings → Music folders to allow it. **Use Android folder picker** is an alternative for granting access to one folder. Previously saved system-picker folders may need a new folder grant after moving storage. Device firmware controls which mounts are accessible.

## Tags or covers are missing

Give visible tracks time to load their embedded metadata. Check that the file actually contains readable tags/artwork and that the format is supported by the device. Refresh the source after changing files. The Folders browser caches metadata in memory; Local library maintains a persistent tag index.

## A Spotify track cannot play

Spotify is metadata-only. Enable at least one audio provider, check its account/subscription, and verify the priority order. Catalogs may not contain the same recording; an unavailable match can fall back to another provider. Temporary failures can be retried.

## Music stops in the middle of a song

Milkbeat buffers up to about 16 MB of a streamed song ahead (usually all of it) and, when a stream link fails, requests a fresh one for the same recording and resumes at the same position. Playback can still stop if the song is not yet buffered far enough when the link fails, for example right after it starts, while a music video's picture shares the buffer, or on a slow network, and the audio provider then cannot supply a new stream in time. Retry later, or check whether YouTube is limiting your network.

## Music becomes silent while the player keeps advancing

Milkbeat checks actual audio-frame progress for normal decoded TV audio (PCM). After about five seconds
without output progress while the player still reports playback, it freezes the displayed position
and attempts to reconnect the same track, preserving the queue.
The player reports recovery until frames advance again. After two unsuccessful attempts, it leaves
the track paused and shows **Audio output stopped. Press Play to retry.** Press Play for another attempt,
or choose another track. Pause, seeking, choosing another track or Stop cancels recovery. This check
uses output progress rather than volume, so a quiet passage alone does not restart the song. Recovery
may repeat a short stretch of the song. Hardware-offloaded audio and encoded HDMI passthrough are
outside automatic stall detection.

## Provider sign-in needs verification

Use the streamed phone sign-in viewer and complete the provider's verification there. The phone and TV must be on the same network. Do not expect a native Spotify password form in Milkbeat.

## Playlists or Liked songs are missing

Check the provider is installed, enabled and signed in. Library merges eligible metadata providers; expired accounts need sign-in again. Refresh a failed section or retry its failed page. Liked videos are intentionally omitted.

## Visuals are slow or black

Enable diagnostics and note the preset, fps, target and render dimensions. Automatic resolution and preset skipping can adapt to load. Check the visualizer is enabled and the device supports OpenGL ES 3.0. Report persistent issues with the device model and app version; a screenshot alone cannot establish an fps improvement or regression.

To lighten the load, set **Detail** lower, **Transition style** to Lightweight, or **Native trails** to Standard in Settings → Visualizations. Resolution and memory budgeting remain automatic. **Skip blank presets** and **Skip slow presets** leave presets that stay black or slow; **Reset skipped presets** brings them back if a skip was wrong.

## A plugin update was not installed with an app update

Enter the plugin's current download link again in Settings → Plugins and review the update. Plugin packages must retain their signing author. Sign-in data is managed separately from the plugin code.


## Private playlist preparation fails

Check that both Spotify and YouTube Music are enabled, signed in and updated to versions that support playlist preparation. Milkbeat re-checks an expired sign-in in the background and resumes preparation when the account still works; YouTube Music 0.2.4 or later is needed so that one refused request does not sign the account out. If the provider confirms the sign-in expired, reopen that plugin's details and sign in through the phone viewer, then select **Retry preparation** on the playlist page.

Interrupted preparation resumes from saved progress. Confirmed unavailable songs are omitted. When a provider briefly refuses or throttles requests, preparation and indexing pause (5, 15, then 45 seconds, or longer if the provider asks) and continue where they were; only if the refusals last does the run stop and try again later in the background. Your own playlists and Liked Songs can prepare in the background, while other playlists prepare when opened. Albums use normal playback and are not mirrored. See [Prepare private playlists](providers.md#prepare-private-playlists).
