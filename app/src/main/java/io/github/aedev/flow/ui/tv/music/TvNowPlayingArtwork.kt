package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/** The artwork view of now-playing: the track's cover filling the screen, edge to edge. */
@Composable
internal fun TvNowPlayingArtwork(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    AsyncImage(
        model = artworkUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize().background(Color.Black),
    )
}
