package io.github.aedev.flow.ui.tv.music

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import kotlinx.coroutines.flow.StateFlow

@Composable
internal fun VisualizerKeepScreenOn(
    view: SurfaceView,
    player: Player?,
    lifecycle: Lifecycle,
    renderReady: StateFlow<Boolean>,
) {
    val ready by renderReady.collectAsStateWithLifecycle()
    DisposableEffect(lifecycle, view, player, ready) {
        val policy = VisualizerScreenOn()
        val handler = Handler(Looper.getMainLooper())
        var started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val timeout = Runnable { view.keepScreenOn = false }

        fun updateScreenOn() {
            handler.removeCallbacks(timeout)
            val keep =
                started && player != null &&
                    policy.update(
                        player.isPlaying,
                        player.playbackState,
                        player.playWhenReady,
                        player.playbackSuppressionReason,
                        SystemClock.elapsedRealtime(),
                        surfaceReady = renderReady.value && view.isAttachedToWindow && view.holder.surface.isValid,
                    )
            view.keepScreenOn = keep
            if (!started || !view.isAttachedToWindow || !view.holder.surface.isValid) policy.reset()
            if (keep && player != null && !player.isPlaying) {
                handler.postDelayed(timeout, policy.remainingBufferingMs(SystemClock.elapsedRealtime()))
            }
        }
        val listener =
            object : Player.Listener {
                override fun onEvents(
                    player: Player,
                    events: Player.Events,
                ) {
                    updateScreenOn()
                }
            }
        player?.addListener(listener)
        val surfaceCallback =
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = updateScreenOn()

                override fun surfaceChanged(
                    holder: SurfaceHolder,
                    format: Int,
                    width: Int,
                    height: Int,
                ) = updateScreenOn()

                override fun surfaceDestroyed(holder: SurfaceHolder) {
                    handler.removeCallbacks(timeout)
                    policy.reset()
                    view.keepScreenOn = false
                }
            }
        val attachListener =
            object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) = updateScreenOn()

                override fun onViewDetachedFromWindow(v: View) {
                    handler.removeCallbacks(timeout)
                    policy.reset()
                    view.keepScreenOn = false
                }
            }
        view.holder.addCallback(surfaceCallback)
        view.addOnAttachStateChangeListener(attachListener)
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        started = true
                        updateScreenOn()
                    }

                    Lifecycle.Event.ON_STOP -> {
                        started = false
                        handler.removeCallbacks(timeout)
                        policy.reset()
                        view.keepScreenOn = false
                    }

                    else -> {
                        Unit
                    }
                }
            }
        lifecycle.addObserver(observer)
        updateScreenOn()
        onDispose {
            handler.removeCallbacks(timeout)
            player?.removeListener(listener)
            view.holder.removeCallback(surfaceCallback)
            view.removeOnAttachStateChangeListener(attachListener)
            view.keepScreenOn = false
            lifecycle.removeObserver(observer)
        }
    }
}
