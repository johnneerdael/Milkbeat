# Draft user guide copy for TV playback and Search

Draft for the corresponding Milkbeat release · 2026-11-23

## Library → Search

Add after the paragraph on provider filters in [Music tabs, Search and Library](../../user-guide/library.md#search).

> To return to what’s already playing, close the on-screen keyboard if it’s open. Press **Down** through Search until the track’s artwork and title are highlighted in the bottom **Now Playing** strip. Press **OK** to open the player. Your track and queue continue where they were. Press **Back** until the player closes, then press **Up** from the strip to return to your search.

## Playback → The full player

Add a cross-reference near the mini-player instructions in [Playback, queue and radio](../../user-guide/playback.md#the-full-player).

> While you’re on Search, you can reopen the player from the bottom **Now Playing** strip. Close the on-screen keyboard first, then press **Down** through Search to the track card and press **OK**.

## The visualizer → How it fits into Milkbeat

Add a bullet alongside the existing visibility guidance in [The visualizer](../../user-guide/visualizer.md#how-it-fits-into-milkbeat).

> **The visuals stay on while you watch.** While ProjectM is on screen and music is playing, Milkbeat keeps Android TV’s screensaver from interrupting the visuals. When you pause or leave the visualizer view, your TV’s usual screensaver settings apply again. Your TV still controls sleep and Energy Saver.

## Settings → Transitions and picture quality

Add a row under Detail in [Settings](../../user-guide/settings.md#transitions-and-picture-quality) if the brightness limiter ships.

| Setting | What it does | Default |
| --- | --- | --- |
| **Brightness limiter** | Dims the visualizer picture to 75%, 50% or 25%. Choose **Off** to show the original picture. | Off |

Add after the table.

> Open **Settings → Visualizations → Picture quality → Brightness limiter**. Choose a lower level if bright presets are uncomfortable while watching in a dark room. The setting applies to the visualizer picture. The controls, cover art, music videos and your TV’s brightness setting keep their usual appearance. **Brightness** in Mood mix picks presets by their brightness. Use **Brightness limiter** to dim all presets.

Add the same setting to [The visualizer’s settings summary](../../user-guide/visualizer.md#settings-at-a-glance) and link to the Settings section. Use “The brightness level takes effect the next time the visualizer is on screen” if the guide needs to describe when the change appears.

## Release notes and publishing

- Fixed the Android TV screensaver interrupting active ProjectM visualizations.
- From Search, press **Down** to the bottom **Now Playing** strip to return to the current track.
- If the limiter ships, add “Dim ProjectM visuals with the optional **Brightness limiter** in Visualizations settings.”

The README already links to the guide. When the limiter ships, add “Use **Brightness limiter** in Visualizations settings to dim bright presets” to its visualizer overview. [README visualizer overview](../../../README.md#the-visualizer)

Replace the picture-quality screenshot once the new row is available on TV. Run `mkdocs build --strict` after transferring this copy to the live guide sources. GitHub Pages serves the guide at the Milkbeat custom domain. [MkDocs workflow](../../user-guide-site.md) · [Milkbeat user guide](https://milkbeat.neerdael.nl/)
