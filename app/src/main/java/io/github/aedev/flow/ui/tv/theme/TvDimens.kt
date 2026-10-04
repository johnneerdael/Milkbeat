package io.github.aedev.flow.ui.tv.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Layout tokens for the ten-foot UI. Overscan values keep interactive content
 * inside the ~5% TV safe area; the rest sizes cards and spacing for D-pad browsing.
 */
@Immutable
data class TvDimens(
    val overscanHorizontal: Dp = 48.dp,
    val overscanVertical: Dp = 24.dp,
    val railEdgePadding: Dp = 32.dp,
    val railCollapsedWidth: Dp = 92.dp,
    val railExpandedWidth: Dp = 300.dp,
    val videoCardWidth: Dp = 260.dp,
    val musicCardWidth: Dp = 180.dp,
    val trackColumnWidth: Dp = 420.dp,
    val trackRowHeight: Dp = 72.dp,
    val collectionAvatarSize: Dp = 44.dp,
    val coverPaneWidth: Dp = 380.dp,
    val coverArtSize: Dp = 200.dp,
    val attributionAvatarSize: Dp = 28.dp,
    val sourceBadgeSize: Dp = 20.dp,
    val portraitSize: Dp = 200.dp,
    val rowSpacing: Dp = 36.dp,
    val itemSpacing: Dp = 20.dp,
    val focusScale: Float = 1.08f,
    val focusBorderWidth: Dp = 2.5.dp,
    val sidePanelWidth: Dp = 400.dp,
    val signInViewportWidth: Dp = 360.dp,
    val signInViewportHeight: Dp = 720.dp,
)

val LocalTvDimens = staticCompositionLocalOf { TvDimens() }
