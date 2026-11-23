package io.github.aedev.flow.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.ForwardingSimpleBasePlayer
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.ListenableFuture

@OptIn(UnstableApi::class)
internal class OutputReportingPlayer(
    player: Player,
    private val onPlayRequest: (Boolean) -> Unit = {},
    private val onSeekRequest: () -> Boolean = { false },
    private val onStopRequest: () -> Unit = {},
    private val audioStartHold: () -> Long? = { null },
) : ForwardingSimpleBasePlayer(player) {
    private var heldPositionMs: Long? = null
    private var retrying = false

    fun hold(
        positionMs: Long,
        retrying: Boolean,
    ) {
        heldPositionMs = positionMs
        this.retrying = retrying
        invalidateState()
    }

    fun clearHold() {
        heldPositionMs = null
        invalidateState()
    }

    override fun getState(): State {
        val state = super.getState()
        val position = heldPositionMs ?: return withAudioStartHold(state)
        if (state.playerError != null || state.timeline.isEmpty) return state
        return withAudioStartHold(
            state
                .buildUpon()
                .setContentPositionMs(position)
                .setPlaybackState(if (retrying) Player.STATE_BUFFERING else Player.STATE_READY)
                .setPlayWhenReady(retrying && state.playWhenReady, state.playWhenReadyChangeReason)
                .setIsLoading(retrying && state.isLoading)
                .build(),
        )
    }

    /**
     * While the listener has requested audio and the open has not confirmed output, the exposed
     * state buffers at the attempt's base position instead of advancing a silent clock.
     */
    private fun withAudioStartHold(state: State): State {
        val pending = audioStartHold() ?: return state
        if (state.playerError != null || state.timeline.isEmpty || state.playbackState == Player.STATE_IDLE) return state
        return state
            .buildUpon()
            .setContentPositionMs(pending)
            .setPlaybackState(Player.STATE_BUFFERING)
            .setIsLoading(true)
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        onPlayRequest(playWhenReady)
        return super.handleSetPlayWhenReady(playWhenReady)
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        val prepare = onSeekRequest()
        val result = super.handleSeek(mediaItemIndex, positionMs, seekCommand)
        if (prepare) player.prepare()
        return result
    }

    override fun handleStop(): ListenableFuture<*> {
        onStopRequest()
        return super.handleStop()
    }
}
