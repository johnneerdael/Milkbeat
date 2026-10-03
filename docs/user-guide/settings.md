# Settings reference

[User guide](index.md)

Settings uses a category list on the left and its controls on the right. Change only the options relevant to your listening setup.

## Plugins

Install packages, select metadata/audio/video roles, set audio priority, manage provider sign-ins and play-history reporting, and index supported provider playlists. See [Providers](providers.md).

![Installed plugins and the three provider roles](images/plugins-current.png)

## Music folders

Choose local folders, add SMB shares, test access, and edit or remove sources. See [Folders](folders.md).

![Music folders settings with local and SMB source actions](images/settings-folders.png)

## Playback

| Setting | Purpose |
| --- | --- |
| Background Play | Allow playback to continue outside the app |
| Autoplay related videos | Play a suggested video when nothing is queued |
| Subtitles | Set the default subtitle preference |
| Skip silence | Skip supported silent sections |
| Stable Voice | Normalize supported audio for more consistent volume |
| Ambient mode | Use the existing video ambient background effect |

![Playback settings: background play, autoplay, subtitles, silence, volume and ambient mode](images/settings-playback.png)

## Visualizations

The projectM settings from ProjectM TV, applied to Milkbeat's own visualizer. Anything you never change keeps the default ProjectM TV picks for your device; settings with several values open in a side panel that marks that default. Changes take effect the next time now playing shows the visualizer.

![Visualizations settings](images/settings-visualizations-enabled.png)

| Setting | Purpose |
| --- | --- |
| Enable visualizations | Enable projectM on supported devices |
| Show music videos | Start supported video tracks with their picture instead of visuals |

### Presets

| Setting | Purpose | Default |
| --- | --- | --- |
| Change presets automatically | Move on to another preset after the preset duration. Left and Right on the remote always switch | On |
| Preset duration | How long a preset plays before the next one: 10 to 90 s | 30 s |
| Music category | Prefer presets tagged for a kind of music. Only categories that hold presets are offered | All |
| Cut on loud beats | A loud beat can cut to the next preset before its time is up | Off |
| Skip blank presets | Leave presets that stay black, often because a texture is missing | On |
| Skip slow presets | Leave presets that stay far below the frame rate even at the lowest resolution | On |
| Reset skipped presets | Bring back every preset skipped as blank or slow | — |

![Preset settings](images/visualizer-settings-presets.png)

![Preset duration picker with the device default marked](images/visualizer-picker-preset-duration.png)

![Music category picker](images/visualizer-picker-music-category.png)

![Reset skipped presets after resetting](images/visualizer-reset-skipped-after.png)

### Transitions

| Setting | Purpose | Default |
| --- | --- | --- |
| Transition length | How long one preset blends into the next, from instant to 10 s. Two presets render during a blend | 7 s (2 s on low-end devices) |
| Transition style | **Auto** blends at a lower resolution that adapts to keep the frame rate. **Lightweight** cuts while the old preset fades out on top, the cheapest. **Classic** is projectM's own blend at full resolution, the heaviest | Auto |

![Transition length picker](images/visualizer-picker-transition-length.png)

![Transition style picker](images/visualizer-picker-transition-style.png)

### Picture quality

| Setting | Purpose | Default |
| --- | --- | --- |
| Render resolution | **Auto** adapts the render size to keep the frame rate smooth; a fixed height renders every preset at that size. Heights are limited to your screen and the memory limit | Auto |
| Frame rate | Your display's refresh rate divided by 1, 2 or 4, at least 24 fps. A lower rate leaves room for a sharper picture | 30 fps |
| Detail | The per-vertex mesh, from Minimal to Ultra: finer warps and zooms at more processor time per frame | Medium (by device) |
| Memory limit | Caps the render height on devices with little memory so Android keeps room for the music. On devices with enough memory it has no effect | On |
| Show diagnostics | Show frame rate, render size, transition, audio level and preset in the playback controls | Off |

![Picture quality settings](images/visualizer-settings-quality.png)

![Render resolution picker](images/visualizer-picker-resolution.png)

![Frame rate picker](images/visualizer-picker-frame-rate.png)

![Detail picker](images/visualizer-picker-detail.png)

### Visualizer timing

**Timing offset** moves the visuals from −100 ms to +200 ms against the sound. Raise it if the visuals come after the beat you hear, lower it if they come before.

![Visualizer timing](images/visualizer-settings-timing.png)

![Timing offset picker](images/visualizer-picker-timing.png)

See [Playback and visuals](playback.md) for what these settings look like in now playing.

## Video playback without settings

Video tracks pick their own quality and codec automatically for your TV and the available streams, and SponsorBlock is always on for supported videos. Neither has a setting.

## About

View app/build information and update controls. The GitHub build provides automatic app updates and manual update checking. Update availability depends on the release service and device installer. The FOSS flavor does not include the app updater.

![About with build information and automatic app updates](images/settings-about.png)

App updates and plugin updates are separate. Installing a signed release over the existing Milkbeat package preserves app data; uninstalling first does not.
