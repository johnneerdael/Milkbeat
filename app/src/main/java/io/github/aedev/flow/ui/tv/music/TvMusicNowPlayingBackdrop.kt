package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.media3.common.Player
import io.github.aedev.flow.data.local.NowPlayingView

@Composable
internal fun TvMusicNowPlayingBackdrop(
    view: NowPlayingView,
    player: Player?,
    trackKey: String?,
    visualizer: TvNowPlayingVisual?,
    artworkUrl: String?,
    fallbackArtworkUrl: String? = null,
    modifier: Modifier = Modifier,
) {
    val videoRequested = view == NowPlayingView.VIDEO
    var previousView by remember {
        mutableStateOf(if (visualizer != null) NowPlayingView.VISUALIZER else NowPlayingView.STATIC)
    }
    SideEffect { if (!videoRequested) previousView = view }
    val pictureReady = remember(player, trackKey, videoRequested) { mutableStateOf(false) }
    val loading = remember(player, trackKey, videoRequested) { mutableStateOf(false) }
    val backdrop = if (videoRequested) previousView else view
    Box(modifier.fillMaxSize()) {
        if (videoRequested) {
            TvMusicVideoSurface(
                player = player,
                modifier = Modifier.fillMaxSize(),
                showLoadingIndicator = false,
                onPictureReadyChanged = { pictureReady.value = it },
                onLoadingChanged = { loading.value = it },
            )
        }
        // Keep this composition in the same slot while video is requested and cancelled.
        if (!videoRequested || !pictureReady.value) {
            if (backdrop == NowPlayingView.VISUALIZER && visualizer != null) {
                visualizer.background()
            } else {
                TvNowPlayingArtwork(artworkUrl = artworkUrl, fallbackArtworkUrl = fallbackArtworkUrl)
            }
        }
        if (videoRequested && loading.value) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }
}
