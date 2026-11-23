package io.github.aedev.flow.ui.tv.music

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player

@Composable
internal fun VisualizerKeepScreenOn(
    view: View,
    player: Player?,
    lifecycle: Lifecycle,
) {
    DisposableEffect(lifecycle, view, player) {
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
                    )
            view.keepScreenOn = keep
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
            view.keepScreenOn = false
            lifecycle.removeObserver(observer)
        }
    }
}
