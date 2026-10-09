package io.github.aedev.flow.ui.tv.music

import androidx.annotation.OptIn
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import io.github.aedev.flow.player.EnhancedMusicPlayerManager

/**
 * A music video's picture behind now-playing: the music player's own video output, without controls.
 * The picture plays only while this is on screen and the app is visible.
 */
@OptIn(UnstableApi::class)
@Composable
fun TvMusicVideoSurface(
    player: Player?,
    modifier: Modifier = Modifier,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val videoPrepared by EnhancedMusicPlayerManager.videoPrepared.collectAsStateWithLifecycle()
    var showJoinIndicator by remember(lifecycle) { mutableStateOf(false) }
    val joining = remember(lifecycle) { TvMusicVideoJoinObserver(lifecycle) { showJoinIndicator = it } }
    DisposableEffect(joining) {
        onDispose { joining.close() }
    }
    DisposableEffect(lifecycle) {
        var acquired = false

        fun hold(visible: Boolean) {
            if (visible == acquired) return
            acquired = visible
            if (visible) EnhancedMusicPlayerManager.acquireVideoSurface() else EnhancedMusicPlayerManager.releaseVideoSurface()
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> hold(true)
                    Lifecycle.Event.ON_STOP -> hold(false)
                    else -> Unit
                }
            }
        hold(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            hold(false)
        }
    }
    val shutter = MaterialTheme.colorScheme.scrim.toArgb()
    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setShutterBackgroundColor(shutter)
                }
            },
            update = {
                joining.bind(it, player, videoPrepared)
                it.player = player.takeIf { videoPrepared }
            },
            onRelease = {
                joining.releaseSurface(it)
                it.player = null
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (showJoinIndicator) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}
