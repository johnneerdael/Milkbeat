package io.github.aedev.flow.ui.tv.music

import androidx.media3.common.Player

internal class VisualizerScreenOn {
    private var bufferingSince: Long? = null
    private var wasPlaying = false

    fun update(
        isPlaying: Boolean,
        playbackState: Int,
        playWhenReady: Boolean,
        suppressionReason: Int,
        nowMs: Long,
    ): Boolean {
        if (isPlaying) {
            wasPlaying = true
            bufferingSince = null
            return true
        }
        if (wasPlaying && playbackState == Player.STATE_BUFFERING && playWhenReady &&
            suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        ) {
            val start = bufferingSince ?: nowMs.also { bufferingSince = it }
            return nowMs - start < BUFFERING_GRACE_MS
        }
        reset()
        return false
    }

    fun reset() {
        wasPlaying = false
        bufferingSince = null
    }

    fun remainingBufferingMs(nowMs: Long): Long = (BUFFERING_GRACE_MS - (nowMs - (bufferingSince ?: nowMs))).coerceAtLeast(0L)

    companion object {
        const val BUFFERING_GRACE_MS = 30_000L
    }
}
