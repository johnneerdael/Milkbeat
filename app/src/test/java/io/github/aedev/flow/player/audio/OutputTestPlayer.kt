package io.github.aedev.flow.player.audio

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

internal class OutputTestPlayer : SimpleBasePlayer(Looper.getMainLooper()) {
    var positionMs = 167_000L
    var prepares = 0
    var stops = 0
    var prepareReady = true
    var onPrepare: () -> Unit = {}
    private var value =
        State
            .Builder()
            .setAvailableCommands(
                Player.Commands
                    .Builder()
                    .addAll(
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_METADATA,
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_STOP,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_SEEK_TO_MEDIA_ITEM,
                        Player.COMMAND_CHANGE_MEDIA_ITEMS,
                    ).build(),
            ).setPlaylist(
                listOf("first", "second").map { id ->
                    MediaItemData
                        .Builder(id)
                        .setMediaItem(
                            MediaItem
                                .Builder()
                                .setMediaId(id)
                                .setUri("file:///$id.wav")
                                .build(),
                        ).setDurationUs(600_000_000)
                        .build()
                },
            ).setCurrentMediaItemIndex(0)
            .setPlaybackState(Player.STATE_READY)
            .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .build()

    override fun getState(): State = value.buildUpon().setContentPositionMs(PositionSupplier { positionMs }).build()

    fun transition(index: Int) {
        positionMs = 0
        value = value.buildUpon().setCurrentMediaItemIndex(index).build()
        invalidateState()
    }

    fun buffering() {
        value = value.buildUpon().setPlaybackState(Player.STATE_BUFFERING).build()
        invalidateState()
    }

    fun suppress() {
        value = value.buildUpon().setPlaybackSuppressionReason(Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS).build()
        invalidateState()
    }

    fun loseFocus() {
        value = value.buildUpon().setPlayWhenReady(false, Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS).build()
        invalidateState()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        value = value.buildUpon().setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST).build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        prepares++
        onPrepare()
        value = value.buildUpon().setPlaybackState(if (prepareReady) Player.STATE_READY else Player.STATE_BUFFERING).build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        stops++
        value = value.buildUpon().setPlaybackState(Player.STATE_IDLE).build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(
        mediaItemIndex: Int,
        positionMs: Long,
        seekCommand: Int,
    ): ListenableFuture<*> {
        this.positionMs = positionMs
        value = value.buildUpon().setCurrentMediaItemIndex(mediaItemIndex).build()
        invalidateState()
        return Futures.immediateVoidFuture()
    }
}
