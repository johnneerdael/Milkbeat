package io.github.aedev.flow.ui.tv.music

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView

/** Observe Media3's existing output; never issue a transport, selection, focus or source command. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class TvMusicVideoJoinObserver(
    private val lifecycle: Lifecycle,
    private val onIndicatorChanged: (Boolean) -> Unit,
) : Player.Listener,
    SurfaceHolder.Callback {
    private val state = TvMusicVideoJoinState()
    private var player: Player? = null
    private var holder: SurfaceHolder? = null
    private var view: PlayerView? = null
    private var picturePrepared = false
    private var observingLifecycle = false
    private val lifecycleObserver = LifecycleEventObserver { _, _ -> refreshVisibility() }

    /** Call before PlayerView.setPlayer so even an immediately rendered first frame is observed. */
    fun bind(
        view: PlayerView,
        nextPlayer: Player?,
        prepared: Boolean = true,
    ) {
        this.view = view
        if (!prepared && picturePrepared) state.itemChanged()
        picturePrepared = prepared
        if (!observingLifecycle) {
            observingLifecycle = true
            lifecycle.addObserver(lifecycleObserver)
            refreshVisibility()
        }
        val nextHolder = (view.videoSurfaceView as? SurfaceView)?.holder
        if (holder !== nextHolder) {
            holder?.removeCallback(this)
            holder = nextHolder
            state.surfaceChanged(nextHolder?.surface?.isValid == true)
            nextHolder?.addCallback(this)
        }
        if (player !== nextPlayer) {
            player?.removeListener(this)
            player = nextPlayer
            state.itemChanged()
            state.playWhenReady = nextPlayer?.playWhenReady == true
            nextPlayer?.addListener(this)
        }
        publish()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (holder !== this.holder) return
        state.surfaceChanged(true)
        publish()
    }

    override fun surfaceChanged(
        holder: SurfaceHolder,
        format: Int,
        width: Int,
        height: Int,
    ) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (holder !== this.holder) return
        state.surfaceChanged(false)
        publish()
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        state.itemChanged()
        publish()
    }

    override fun onPlayWhenReadyChanged(
        playWhenReady: Boolean,
        reason: Int,
    ) {
        state.playWhenReady = playWhenReady
        publish()
    }

    override fun onRenderedFirstFrame() {
        if (!picturePrepared || view?.player !== player || holder?.surface?.isValid != true) return
        state.onRenderedFirstFrame()
        publish()
    }

    private fun refreshVisibility() {
        state.visible = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        publish()
    }

    private fun publish() = onIndicatorChanged(state.showIndicator)

    fun releaseSurface(view: PlayerView) {
        val released = (view.videoSurfaceView as? SurfaceView)?.holder
        if (holder !== released) return
        holder?.removeCallback(this)
        holder = null
        this.view = null
        state.surfaceChanged(false)
    }

    fun close() {
        player?.removeListener(this)
        player = null
        holder?.removeCallback(this)
        holder = null
        view = null
        lifecycle.removeObserver(lifecycleObserver)
        observingLifecycle = false
    }
}
