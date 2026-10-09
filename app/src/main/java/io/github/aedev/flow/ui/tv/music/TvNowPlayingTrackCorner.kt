package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.ui.components.shared.titleMarquee

private val CoverSize = 96.dp
private const val FRINGE_ALPHA = 0.7f

/** What the corner shows for one track. */
internal data class CornerTrack(
    val artist: String,
    val title: String,
    val artworkUrl: String?,
)

/**
 * The always-visible track identity for the now-playing screen: cover art with the artist and, below
 * it, the title, each on one line, straight over the visual; a line longer than [maxWidth] allows
 * scrolls once. While [next] is set, [glitch] hands the corner over to it slice by slice; the frames
 * are read in the draw phase, so the hand-over never recomposes.
 */
@Composable
internal fun TvNowPlayingTrackCorner(
    current: CornerTrack,
    next: CornerTrack?,
    glitch: () -> GlitchFrame?,
    contentColor: Color,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    statusText: String? = null,
) {
    val fringe = rememberFringePaints(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
    val coverBands = { glitch()?.cover }
    val artistBands = { glitch()?.artist }
    val titleBands = { glitch()?.title }
    val split = { glitch()?.colorSplit ?: 0f }
    val artistStyle = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Normal)
    val titleStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal)

    Row(
        modifier = modifier.widthIn(max = maxWidth),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraSmall,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Box(Modifier.size(CoverSize)) {
                CornerLayers(current, next) { track, showsNext ->
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = track.title.takeUnless { showsNext },
                        modifier = Modifier.size(CoverSize).glitchLayer(showsNext, coverBands, split, fringe),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
                CornerLayers(current, next) { track, showsNext ->
                    CornerLine(
                        track.artist,
                        artistStyle,
                        contentColor,
                        scrolls = !showsNext,
                        Modifier.glitchLayer(showsNext, artistBands, split, fringe),
                    )
                }
            }
            Box {
                CornerLayers(current, next) { track, showsNext ->
                    CornerLine(
                        track.title,
                        titleStyle,
                        contentColor.copy(alpha = 0.8f),
                        scrolls = !showsNext,
                        Modifier.glitchLayer(showsNext, titleBands, split, fringe),
                    )
                }
            }
            statusText?.let { text ->
                CornerLine(text, MaterialTheme.typography.bodyMedium, contentColor, scrolls = false, modifier = Modifier)
            }
        }
    }
}

@Composable
private fun CornerLayers(
    current: CornerTrack,
    next: CornerTrack?,
    layer: @Composable (CornerTrack, Boolean) -> Unit,
) {
    // Keyed by track so a layer keeps its loaded artwork when the track moves from incoming to shown.
    key(current) { layer(current, false) }
    if (next != null) key(next) { layer(next, true) }
}

@Composable
private fun CornerLine(
    text: String,
    style: TextStyle,
    color: Color,
    scrolls: Boolean,
    modifier: Modifier,
) {
    // Only the shown track scrolls: the incoming one is drawn in glitch slices and must not animate unseen.
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = if (scrolls) modifier.titleMarquee() else modifier,
    )
}

private class FringePaints(
    val left: Paint,
    val right: Paint,
)

@Composable
private fun rememberFringePaints(
    left: Color,
    right: Color,
): FringePaints =
    remember(left, right) {
        FringePaints(
            left = Paint().apply { colorFilter = ColorFilter.tint(left, BlendMode.Modulate) }.also { it.alpha = FRINGE_ALPHA },
            right = Paint().apply { colorFilter = ColorFilter.tint(right, BlendMode.Modulate) }.also { it.alpha = FRINGE_ALPHA },
        )
    }

/**
 * Draws this layer's share of the glitch: the bands that come from its track, each shifted sideways,
 * with colour fringes either side. Without a glitch frame only the playing track's layer draws.
 */
private fun Modifier.glitchLayer(
    showsNext: Boolean,
    bands: () -> List<GlitchBand>?,
    split: () -> Float,
    fringe: FringePaints,
): Modifier =
    drawWithContent {
        val active = bands()
        if (active == null) {
            if (!showsNext) drawContent()
            return@drawWithContent
        }
        val splitPx = split() * size.width
        active.forEach { band ->
            if (band.showsNext != showsNext) return@forEach
            clipRect(top = band.top * size.height, bottom = band.bottom * size.height) {
                translate(left = band.shift * size.width) {
                    if (splitPx > 0f) {
                        this@drawWithContent.drawFringe(fringe.left, -splitPx)
                        this@drawWithContent.drawFringe(fringe.right, splitPx)
                    }
                    this@drawWithContent.drawContent()
                }
            }
        }
    }

private fun ContentDrawScope.drawFringe(
    paint: Paint,
    dx: Float,
) {
    drawIntoCanvas { canvas ->
        canvas.saveLayer(Rect(-size.width, 0f, size.width * 2f, size.height), paint)
        translate(left = dx) { this@drawFringe.drawContent() }
        canvas.restore()
    }
}
