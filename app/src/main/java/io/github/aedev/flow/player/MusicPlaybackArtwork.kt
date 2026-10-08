package io.github.aedev.flow.player

/** A selected source's artwork is only usable by that source's original queue item. */
data class MusicPlaybackArtwork(
    val mediaId: String,
    val url: String?,
) {
    fun forTrack(trackId: String?): String? = url?.takeIf { mediaId == trackId && it.isNotBlank() }
}
