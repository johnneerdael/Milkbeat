# Playback, queues and visuals

[User guide](index.md)

## Player controls

Press OK in the full music player to show seeking, shuffle, previous, play/pause, next, repeat, like, the video/visualizer switch and the queue. Back closes the current panel or player surface.

![Player controls over projectM visuals](images/player-controls.png)

## Queues and preparation

Open the queue to inspect upcoming tracks or jump to one. Holding Up or Down accelerates scrolling. Queue preparation follows playback order and matches the entire remaining queue one track at a time while music plays. Jumping elsewhere gives the new playback position priority. Tracks with native audio IDs do not need cross-provider matching.

Confirmed unmatched tracks can be removed from the future queue. Temporary failures remain retryable. Preparation reduces the work needed at a transition, but does not guarantee gapless playback under every network, format or provider condition.

![Upcoming queue beside the player controls](images/queue-open.png)

## Mixes

Once a streaming queue starts playing, Milkbeat seeds a mix from the queue's first song. When the metadata provider has no radio, Milkbeat tries compatible audio providers in priority order.

For normal Spotify playback through YouTube, that first song's matched YouTube ID seeds the radio. [Private playlist preparation](providers.md#why-prepare-a-youtube-playlist) instead gives YouTube the whole matched playlist as its native autoplay context. Milkbeat can then exclude the playlist's matched songs from later suggestions, helping avoid repeats as the radio continuation begins.

Every queue continues as a radio: a [prepared private playlist](providers.md#prepare-private-playlists) from its YouTube copy, anything else from its first song. All the presets YouTube Music offers for that radio (**All**, **Popular**, **Discover**, **Deep cuts**, moods and decades) appear in one scrolling line above the queue; press Right on a queue track to reach them and Down to return to it. Changing the mode replaces upcoming radio-added songs while keeping the playing song, the original playlist and tracks you added yourself. Continuations stay in the selected mode, and the controls remain visible as more suggestions load. The controls stay pinned above the scrolling rows; press Back to close the queue. Radio suggestions are not saved into a [prepared private playlist](providers.md#prepare-private-playlists).

Press Right on a queue track to focus the pinned radio presets. Move Left or Right between presets, then press Down to return to the track you left, with the queue's scroll position retained. If that track was removed while you changed the mix, focus falls back to an available row.

Local folder playback is independent of provider radio. **Play folder** queues tracks from the current folder.

## MilkDrop visualizer

The visualizer uses projectM through ProjectM TV, with 9,606 Cream of the Crop presets. Its input comes from Milkbeat's player. With controls hidden, Left and Right step through presets.

Out of the box the engine uses ProjectM TV's defaults for your device: 30 fps, automatic resolution, automatic transitions, a memory limit and slow-preset skipping. The target is not a promise that every preset reaches 30 fps; performance varies by preset and device. Every one of these can be changed in [Settings → Visualizations](settings.md#visualizations).

Rendering and audio monitoring stop when the visualizer leaves the screen or the app goes into the background. A visible visualizer can keep drifting on silence when music is paused.

![ProjectM visualizer in the updated TV release](images/player-start.png)

## Timing and diagnostics

Settings → Visualizations contains the visualizer switch, music-video default, the projectM settings, diagnostics and the timing offset. Raise the timing value if the visuals arrive after the beat; lower it if they arrive before. Adjust by listening and watching on your own audio setup.

Diagnostics show measured fps, target fps, render dimensions and whether the size is automatic or fixed, transition state, audio level and preset. Use these values when investigating slow visuals instead of judging performance from a still screenshot.

![Diagnostics with the defaults: 30 fps target, automatic resolution, adaptive blend](images/visualizer-diagnostics-defaults.png)

## What the visualizer settings change

The diagnostics line shows each setting at work. With **Render resolution** at 720p and **Frame rate** at 60 fps, the line reads `of 60 fps · 1280×720 fixed`:

![Fixed 720p at 60 fps](images/visualizer-effect-720p-60fps.png)

With **Transition style** set to Lightweight, presets cut and the old one fades out on top; the line reads `lightweight` instead of `blend`:

![Lightweight transitions](images/visualizer-effect-lightweight-transition.png)

With **Change presets automatically** off, a preset stays until you press Left or Right. With **Cut on loud beats** on, a loud beat can cut to the next preset before its duration is up.

![A preset held with automatic changes off](images/visualizer-effect-auto-change-off.png)

![A preset reached by a cut on a loud beat](images/visualizer-effect-beat-cuts.png)

## Music videos

Supported music-video tracks can switch from visuals to their picture while audio continues. Unsupported or unavailable video can fall back to audio and visuals. **Show music videos** changes the starting preference; the player switch changes it for the current session.

![Full-screen music-video example from the existing README](images/music-video.jpg)
