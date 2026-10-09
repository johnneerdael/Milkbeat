# Troubleshooting

## A provider's tab is missing or empty

A provider gets its own tab once its plugin is installed and enabled and you are signed in
(YouTube Music needs no sign-in). If a provider's tab is missing, check Settings → Plugins. Local
music is in the **Local library** tab once a music folder is linked, and in Library → Folders.
When a provider's Home has nothing to show, select **Retry** to ask for it again.

## A music tab says Couldn't load content

YouTube Video 0.2.0 could fail to load Home when Liked Music had no songs. Update that plugin
to 0.2.1 or later in Settings → Plugins → Update all plugins, then retry Home. An empty Liked
Music page is normal and does not require another sign-in.

The message below the title is the provider's reason. A temporary failure, such as a dropped connection, a rate limit or a one-off server error, is retried automatically up to three times over about a minute, or later if the provider asks to wait. Select **Retry** to try again at once; the button reads **Trying again…** while it does. Other failures, such as a missing sign-in, are not retried automatically: press Left to reach the navigation rail and open Settings → Plugins.

Automatic retries wait while you are on another tab or screen, or the app is in the background, then resume when you return to that tab. This only pauses requests for the Home feed; music playback and queue preparation continue.

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

Give visible tracks time to load their embedded metadata. Check that the file actually contains readable tags/artwork and that the format is supported by the device. Refresh the source after changing files, and select **Rescan library** in Settings → Music folders so the Local library reads the new tags. The Folders browser caches metadata in memory; the Local library keeps a persistent tag index.

## A Spotify track cannot play

Spotify has no audio of its own. Enable at least one audio provider, check its account/subscription. Milkbeat races acceptable matches across enabled providers. Catalogs may not contain the same recording; an unavailable match can fall back to another provider. Temporary failures can be retried.

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

Use the streamed phone sign-in viewer and complete the provider's verification there. The phone and TV must be on the same network for web sign-in; SoundCloud's TV code works from any network. Milkbeat has no native password form for these providers.

## Playlists or Liked songs are missing

Check the provider is installed, enabled and signed in: Library shows a chip only for signed-in providers with a library, and **Playlists** combines every signed-in provider. Expired accounts need sign-in again. Refresh a failed section or retry its failed page. Liked videos are intentionally omitted.

## Visuals are slow, black or do not react

1. Check that **Enable visualizations** is on in Settings → Visualizations and that the now-playing view is the visualizer, not video or artwork.
2. Show the controls and read the audio level above the seek bar. **No sound** while music plays means the visualizer is not receiving audio; pause and resume, or restart the track.
3. Turn on **Show diagnostics** and note the preset, measured and target fps and the render size. Automatic resolution and preset skipping adapt to load; a still screenshot cannot show a frame-rate problem.
4. To lighten the load, set **Detail** lower, **Transition style** to Lightweight or **Native trails** to Standard. Resolution and memory budgeting stay automatic.
5. **Skip blank presets** and **Skip slow presets** leave presets that stay black or slow; **Reset skipped presets** brings them back if a skip was wrong.

The device needs OpenGL ES 3.0. For engine-level symptoms, see ProjectM TV's [troubleshooting](https://johnneerdael.github.io/ProjectM-TV/troubleshooting/). Report persistent issues with the device model, the Milkbeat version and the diagnostics line.

## The app closes at preset changes

If Milkbeat closes by itself while the visualizer shows, often when one preset changes into the next:

1. Open Milkbeat again and check **Settings → Visualizations → Last exit**. Select it for the [exit report](settings.md#troubleshooting): *crashed (native code)* or *killed by signal* points to the graphics driver or the engine, *killed for low memory* to memory.
2. Try **Transition style** Lightweight, then Classic. If only Auto blends crash, the lower blend resolution is involved.
3. Turn **Shader binary cache** off. If the crashes stop, the GPU driver cannot reuse program binaries between OpenGL contexts.
4. If they continue, also turn **Background compile** off. Switches then pause the picture while each preset compiles, often for a second or two. If the crashes stop only now, the driver fails when two threads compile at once.
5. Report the result with a photo of the exit report, the device model and which switches you changed. Turn the switches back on afterwards if they made no difference.

These switches are the same as ProjectM TV's; its [troubleshooting](https://johnneerdael.github.io/ProjectM-TV/troubleshooting/#the-app-closes-at-preset-changes) describes them in depth. They exist to diagnose crashes first reported on a Fire TV Stick 4K Max (2nd gen, PowerVR GE9215 GPU). Leave them on otherwise.

## A plugin did not update

App updates do not update plugins. With **Update plugins automatically** on, Milkbeat checks at start and every six hours; an update that asks for new permissions waits until you review it in Settings → Plugins. Select **Update all plugins** to check at once, or enter the plugin's code or link again. An update must keep its signing author and cannot be older than the installed version. Sign-ins are kept separately from the plugin code.

## A plugin needs a newer version of Milkbeat

The plugin was built for a newer plugin API than this Milkbeat supports. Milkbeat refuses the plugin before installing anything and keeps the version you have. Update Milkbeat first: in the GitHub build, select **Update Milkbeat** or the waiting update's row, or open [Settings → About](settings.md#about). Install the FOSS build's update through its store. Then enter the plugin's code again. A waiting plugin update installs on the next check after the app update. [Updates that need a newer Milkbeat](providers.md#updates-that-need-a-newer-milkbeat)

## The sidebar highlights the wrong tab or ignores a press

Milkbeat 0.9.55 and earlier could leave Settings on Back from a Plugins page, show a music page with Settings still highlighted, and then ignore sidebar presses until a restart. Update Milkbeat: the highlighted tab now always follows the page on screen, and pressing the tab that owns the page returns to its first page.

## Private playlist preparation fails

Check that both Spotify and YouTube Music are enabled, signed in and updated to versions that support playlist preparation. Milkbeat re-checks an expired sign-in in the background and resumes preparation when the account still works; YouTube Music 0.2.4 or later is needed so that one refused request does not sign the account out. If the provider confirms the sign-in expired, reopen that plugin's details and sign in through the phone viewer, then select **Retry preparation** on the playlist page.

Interrupted preparation resumes from saved progress. Confirmed unavailable songs are omitted. When a provider briefly refuses or throttles requests, preparation and indexing pause (5, 15, then 45 seconds, or longer if the provider asks) and continue where they were; only if the refusals last does the run stop and try again later in the background. Your own playlists and Liked Songs can prepare in the background, while other playlists prepare when opened. Albums use normal playback and are not mirrored. See [Prepare private playlists](providers.md#prepare-private-playlists).

## Capture slow startup or a gap when switching views

Enable **Settings → Playback → Debug playback logging**, reproduce the issue, then collect
`adb logcat -v threadtime -s MilkbeatTrace:I`. Turn logging off when finished. The setting is off
by default and is not transferred to another device. Events include local play, pause, seek and
view choices; known-ID bypass or matching; provider resolution; runtime queue time; HTTP timing;
and Media3 buffering, decoder and AudioTrack output callbacks. Logs use fixed event names and
numeric fields rather than URLs, credentials or track titles.

A capture without a decoder restart or underrun does not prove that no brief audible gap occurred.
Correlate the view switch with output events and the audible reproduction. Network captures show
request timing; they do not contain the remote-control press or the first audible sample.
