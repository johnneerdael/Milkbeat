package io.github.aedev.flow.ui.tv.music

/** Waiting belongs to an attached surface and item, never to playback READY or a timeout. */
internal class TvMusicVideoJoinState {
    private var surfaceAvailable = false
    private var renderedFirstFrame = false
    private var failed = false
    var visible = false
    var playWhenReady = false

    val pictureReady: Boolean get() = surfaceAvailable && renderedFirstFrame

    val showIndicator: Boolean get() = visible && playWhenReady && !pictureReady && !failed

    fun surfaceChanged(available: Boolean) {
        surfaceAvailable = available
        renderedFirstFrame = false
    }

    fun itemChanged() {
        failed = false
        renderedFirstFrame = false
    }

    fun onPlayerError() {
        failed = true
        renderedFirstFrame = false
    }

    fun onRenderedFirstFrame() {
        if (surfaceAvailable && !failed) renderedFirstFrame = true
    }
}
