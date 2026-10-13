package io.github.aedev.flow.ui.screens.music

import io.github.aedev.flow.data.music.model.MusicTrack

internal data class CollectionPlayback(
    val track: MusicTrack,
    val queue: List<MusicTrack>,
    val startIndex: Int = selectedQueueIndex(track, queue),
)

private fun selectedQueueIndex(
    track: MusicTrack,
    queue: List<MusicTrack>,
): Int {
    val index =
        queue.indexOfFirst {
            if (track.sourcePosition != null) it.sourcePosition == track.sourcePosition else it == track
        }
    return index.coerceAtLeast(0)
}

internal fun songRadioPlayback(track: MusicTrack): CollectionPlayback {
    val seed = track.copy(sourcePosition = null)
    return CollectionPlayback(seed, listOf(seed))
}
