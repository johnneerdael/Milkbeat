# Troubleshooting

[User guide](index.md)

## Music Home is empty

Home requires a metadata plugin. Select one in Settings → Plugins and complete sign-in if its feed requires an account. For local music, use Library → Folders; no metadata plugin is needed.

## An SMB test fails

1. Check the server IP or hostname, share name and port. Enter the server without `smb://`.
2. Confirm the TV can reach the server and that it supports SMB 2/3.
3. Check the optional folder path is inside the selected share.
4. Use a server account with read access. Guest access works only if the server grants it.
5. Use Show password to check newly entered text, then test again.
6. After access is confirmed, select **Save** before leaving the editor.

A successful network connection does not imply permission to read the share. Test access checks directory access too.

## A USB folder is missing

Check that Android has mounted the drive and exposes it in the folder picker. Reconnect storage or grant access to the folder again if necessary. Device firmware controls which locations the picker exposes.

## Tags or covers are missing

Give visible tracks time to load their embedded metadata. Check that the file actually contains readable tags/artwork and that the format is supported by the device. Refresh the source after changing files. Metadata is not stored as a permanent whole-library index.

## A Spotify track cannot play

Spotify is metadata-only. Enable at least one audio provider, check its account/subscription, and verify the priority order. Catalogs may not contain the same recording; an unavailable match can fall back to another provider. Temporary failures can be retried.

## Provider sign-in needs verification

Use the streamed phone sign-in viewer and complete the provider's verification there. The phone and TV must be on the same network. Do not expect a native Spotify password form in Milkbeat.

## Playlists or Liked songs are missing

Check the provider is installed, enabled and signed in. Library merges eligible metadata providers; expired accounts need sign-in again. Refresh a failed section or retry its failed page. Liked videos are intentionally omitted.

## Visuals are slow or black

Enable diagnostics and note the preset, fps, target and render dimensions. Automatic resolution and preset skipping can adapt to load. Check the visualizer is enabled and the device supports OpenGL ES 3.0. Report persistent issues with the device model and app version; a screenshot alone cannot establish an fps improvement or regression.

## A plugin update was not installed with an app update

Enter the plugin's current download link again in Settings → Plugins and review the update. Plugin packages must retain their signing author. Sign-in data is managed separately from the plugin code.


## Private playlist preparation fails

Check that both Spotify and YouTube Music are enabled, signed in and updated to versions that support playlist preparation. If a sign-in expired, reopen that plugin's details and sign in through the phone viewer, then select **Retry preparation** on the playlist page.

Interrupted preparation resumes from saved progress. Confirmed unavailable songs are omitted; temporary connection errors can be retried. Your own playlists and Liked Songs can prepare in the background, while other playlists prepare when opened. Albums use normal playback and are not mirrored. See [Prepare private playlists](providers.md#prepare-private-playlists).
