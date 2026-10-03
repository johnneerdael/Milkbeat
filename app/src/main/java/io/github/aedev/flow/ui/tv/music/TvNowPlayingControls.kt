package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.MusicVideo
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.NowPlayingView
import io.github.aedev.flow.player.RepeatMode
import io.github.aedev.flow.ui.components.musicplayer.controls.PlayerProgressSlider
import io.github.aedev.flow.ui.tv.components.TvIconButton
import io.github.aedev.flow.ui.tv.components.TvIconButtonColors

// Fits beside the open queue panel: screen width minus the panel, the overscan edge and a gap.
private val ControlsWidth = 480.dp
private val ControlButtonSize = 48.dp
private val ControlsPadding = 12.dp

// The controls bar stays see-through so the visual behind it is never lost, but solid enough to read
// over the brightest presets.
private const val CONTROLS_BAR_ALPHA = 0.65f

/** What the controls bar shows; the screen owns the state and the player calls. */
internal data class TvNowPlayingControlsState(
    val isPlaying: Boolean,
    val isLiked: Boolean,
    val shuffleEnabled: Boolean,
    val repeatMode: RepeatMode,
    /** The view on screen, and the one the view button steps to. */
    val view: NowPlayingView,
    val nextView: NowPlayingView,
    val queueOpen: Boolean,
)

internal class TvNowPlayingControlsActions(
    val onSeekTo: (Long) -> Unit,
    val onSeekBarFocusChanged: (Boolean) -> Unit,
    val onToggleShuffle: () -> Unit,
    val onPrevious: () -> Unit,
    val onTogglePlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onToggleRepeat: () -> Unit,
    val onToggleLike: () -> Unit,
    val onNextView: () -> Unit,
    val onToggleQueue: () -> Unit,
)

/**
 * Seek bar and transport for the now-playing screen. The mobile progress slider sits in a focusable
 * shell so D-pad left/right scrub through the screen's scrub controller.
 */
@Composable
internal fun TvNowPlayingControls(
    state: TvNowPlayingControlsState,
    actions: TvNowPlayingControlsActions,
    positionProvider: () -> Long,
    durationMs: Long,
    buttonColors: TvIconButtonColors,
    playPauseFocusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    status: (@Composable () -> Unit)? = null,
) {
    var seekBarFocused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.width(ControlsWidth),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = CONTROLS_BAR_ALPHA),
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(ControlsPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            status?.invoke()
            Surface(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            seekBarFocused = it.isFocused
                            actions.onSeekBarFocusChanged(it.isFocused)
                        }.focusable(),
                shape = MaterialTheme.shapes.large,
                color = if (seekBarFocused) buttonColors.container else Color.Transparent,
            ) {
                PlayerProgressSlider(
                    positionProvider = positionProvider,
                    duration = durationMs,
                    onSeekTo = actions.onSeekTo,
                    isPlaying = state.isPlaying,
                    modifier =
                        Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .focusProperties { canFocus = false },
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TvIconButton(
                    icon = Icons.Outlined.Shuffle,
                    contentDescription = stringResource(R.string.shuffle),
                    onClick = actions.onToggleShuffle,
                    active = state.shuffleEnabled,
                    colors = buttonColors,
                    size = ControlButtonSize,
                )
                TvIconButton(
                    icon = Icons.Outlined.SkipPrevious,
                    contentDescription = stringResource(R.string.previous),
                    onClick = actions.onPrevious,
                    colors = buttonColors,
                    size = ControlButtonSize,
                )
                TvIconButton(
                    icon = if (state.isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                    contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                    onClick = actions.onTogglePlayPause,
                    active = true,
                    colors = buttonColors,
                    size = ControlButtonSize,
                    focusRequester = playPauseFocusRequester,
                )
                TvIconButton(
                    icon = Icons.Outlined.SkipNext,
                    contentDescription = stringResource(R.string.next),
                    onClick = actions.onNext,
                    colors = buttonColors,
                    size = ControlButtonSize,
                )
                TvIconButton(
                    icon = if (state.repeatMode == RepeatMode.ONE) Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                    contentDescription = stringResource(R.string.loop_video),
                    onClick = actions.onToggleRepeat,
                    active = state.repeatMode != RepeatMode.OFF,
                    colors = buttonColors,
                    size = ControlButtonSize,
                )
                TvIconButton(
                    icon = if (state.isLiked) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(R.string.tv_library_likes),
                    onClick = actions.onToggleLike,
                    active = state.isLiked,
                    colors = buttonColors,
                    size = ControlButtonSize,
                )
                // A choice between views rather than an on/off feature: it shows the view on screen,
                // unfilled, and names the one it steps to.
                TvIconButton(
                    icon =
                        when (state.view) {
                            NowPlayingView.VISUALIZER -> Icons.Outlined.GraphicEq
                            NowPlayingView.VIDEO -> Icons.Outlined.MusicVideo
                            NowPlayingView.STATIC -> Icons.Outlined.Image
                        },
                    contentDescription =
                        stringResource(
                            when (state.nextView) {
                                NowPlayingView.VISUALIZER -> R.string.tv_music_show_visualizer
                                NowPlayingView.VIDEO -> R.string.tv_music_show_video
                                NowPlayingView.STATIC -> R.string.tv_music_show_artwork
                            },
                        ),
                    onClick = actions.onNextView,
                    colors = buttonColors,
                    size = ControlButtonSize,
                )
                TvIconButton(
                    icon = Icons.AutoMirrored.Outlined.QueueMusic,
                    contentDescription = stringResource(R.string.tv_player_queue),
                    onClick = actions.onToggleQueue,
                    active = state.queueOpen,
                    colors = buttonColors,
                    size = ControlButtonSize,
                )
            }
        }
    }
}
