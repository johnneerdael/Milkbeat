package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/** The accepted playback thumbnail fills the screen; failed images use the catalog cover. */
@Composable
internal fun TvNowPlayingArtwork(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
    fallbackArtworkUrl: String? = null,
) {
    var useFallback by remember(artworkUrl, fallbackArtworkUrl) { mutableStateOf(false) }
    val selected = artworkUrl?.takeIf(String::isNotBlank)
    AsyncImage(
        model = if (useFallback) fallbackArtworkUrl else selected ?: fallbackArtworkUrl,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize().background(Color.Black),
        onError = { if (selected != fallbackArtworkUrl) useFallback = true },
    )
}
