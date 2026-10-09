package io.github.aedev.flow.ui.tv.music

/** Waiting belongs to an attached surface and item, never to playback READY or a timeout. */
internal class TvMusicVideoJoinState {
    private var surfaceAvailable = false
    private var renderedFirstFrame = false
    var visible = false
    var playWhenReady = false

    val showIndicator: Boolean get() = visible && playWhenReady && !renderedFirstFrame

    fun surfaceChanged(available: Boolean) {
        surfaceAvailable = available
        renderedFirstFrame = false
    }

    fun itemChanged() {
        renderedFirstFrame = false
    }

    fun onRenderedFirstFrame() {
        if (surfaceAvailable) renderedFirstFrame = true
    }
}
