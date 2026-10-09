package io.github.aedev.flow.ui.tv.music

import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.data.local.NowPlayingView
import io.github.aedev.flow.data.local.nextNowPlayingView
import io.github.aedev.flow.data.local.shownNowPlayingView
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.resolutionStatus
import io.github.aedev.flow.ui.components.shared.rememberMediaPalette
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.tv.components.TvIconButtonColors
import io.github.aedev.flow.ui.tv.input.TvPlayerAction
import io.github.aedev.flow.ui.tv.input.TvPlayerKeyMapper
import io.github.aedev.flow.ui.tv.player.state.TvOverlayMode
import io.github.aedev.flow.ui.tv.player.state.TvPlayerOverlayController
import io.github.aedev.flow.ui.tv.player.state.TvScrubController
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

private enum class TvMusicPanel { NONE, QUEUE }

private const val CORNER_WIDTH_FRACTION = 0.6f
private val PanelGap = 24.dp

/**
 * Full-screen music now-playing: the track sits in the top-left corner over a full-screen backdrop
 * (the artwork, or the [visualizer]), and the seek bar and transport occupy the bottom-left corner
 * only while the remote is in use — they hide after [TvPlayerOverlayController.AUTO_HIDE_DELAY_MS]
 * without a key press. While they are hidden, left and right step the visualizer's presets.
 */
@Composable
fun TvMusicNowPlayingScreen(
    viewModel: MusicPlayerViewModel,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
    visualizer: TvNowPlayingVisual? = null,
    view: NowPlayingView = NowPlayingView.VISUALIZER,
    onViewChange: (NowPlayingView) -> Unit = {},
) {
    val tuning by viewModel.radioTuning.state.collectAsStateWithLifecycle()
    val manager = EnhancedMusicPlayerManager
    val track by manager.currentTrack.collectAsStateWithLifecycle()
    val resolution by manager.resolutionStatus.collectAsStateWithLifecycle()
    val playbackArtwork by manager.playbackArtwork.collectAsStateWithLifecycle()
    val playerState by manager.playerState.collectAsStateWithLifecycle()
    val radioLoading by manager.radioLoading.collectAsStateWithLifecycle()
    LaunchedEffect(playerState.isEnded, radioLoading) {
        if (playerState.isEnded && !radioLoading) {
            withFrameNanos { }
            if (manager.playerState.value.isEnded && !manager.radioLoading.value) onCollapse()
        }
    }
    val shuffleEnabled by manager.shuffleEnabled.collectAsStateWithLifecycle()
    val repeatMode by manager.repeatMode.collectAsStateWithLifecycle()
    val isLiked by manager.isLiked.collectAsStateWithLifecycle()
    val queue by manager.queue.collectAsStateWithLifecycle()
    val queueIndex by manager.currentQueueIndex.collectAsStateWithLifecycle()
    val automix by manager.automixItems.collectAsStateWithLifecycle()
    val showsVideo by manager.videoShown.collectAsStateWithLifecycle()
    val videoAvailable by manager.videoAvailable.collectAsStateWithLifecycle()
    val shownView = shownNowPlayingView(view, showsVideo, visualizerAvailable = visualizer != null)
    val nextView =
        nextNowPlayingView(
            shown = shownView,
            videoAvailable = videoAvailable,
            visualizerAvailable = visualizer != null,
        )
    val dimens = LocalTvDimens.current

    val artworkUrl = track?.highResThumbnailUrl
    val palette = rememberMediaPalette(artworkUrl)
    // Translucent chips over the always-dark backdrop; latched toggles
    // (like, shuffle, repeat, panels) light up with the artwork accent.
    val playerButtonColors =
        remember(palette.accent) {
            TvIconButtonColors(
                container = Color.White.copy(alpha = 0.12f),
                content = Color.White,
                focusedContainer = Color.White,
                focusedContent = Color.Black,
                activeContainer = palette.accent,
                activeContent = if (palette.accent.luminance() > 0.5f) Color.Black else Color.White,
            )
        }

    var panel by rememberSaveable { mutableStateOf(TvMusicPanel.NONE) }
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
    val upcoming = remember(queue, queueIndex, automix, repeatMode) { upcomingTrack(queue, queueIndex, automix, repeatMode) }
    val handOver =
        rememberCornerHandOver(
            current = CornerTrack(track?.artist.orEmpty(), track?.title.orEmpty(), artworkUrl),
            trackKey = track?.videoId,
            upcoming = upcoming?.let { CornerTrack(it.artist, it.title, it.highResThumbnailUrl) },
            isPlaying = playerState.isPlaying,
            positionMs = playerState.position,
            durationMs = playerState.duration,
            positionNow = manager::getCurrentPosition,
            durationNow = manager::getDuration,
        )
    val overlay = remember { TvPlayerOverlayController(System::currentTimeMillis) }
    val overlayState by overlay.state.collectAsStateWithLifecycle()
    val controlsVisible = overlayState.mode != TvOverlayMode.HIDDEN
    val scrubController = remember { TvScrubController() }
    var scrubUiState by remember { mutableStateOf(TvScrubController.ScrubState()) }
    var seekBarFocused by remember { mutableStateOf(false) }
    val playPauseFocusRequester = remember { FocusRequester() }
    val hiddenFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) { overlay.showTransport() }

    val autoHideAt =
        if (panel == TvMusicPanel.NONE) overlay.autoHideDeadline(playerState.isPlaying, scrubUiState.isScrubbing) else null
    LaunchedEffect(autoHideAt, overlayState.lastInteractionAtMs) {
        val deadline = autoHideAt ?: return@LaunchedEffect
        delay((deadline - System.currentTimeMillis()).coerceAtLeast(0L))
        overlay.hide()
    }

    // With the controls gone, an invisible target keeps focus on this screen so the next key reaches it.
    LaunchedEffect(panel, controlsVisible) {
        if (panel == TvMusicPanel.NONE) {
            delay(80)
            runCatching { if (controlsVisible) playPauseFocusRequester.requestFocus() else hiddenFocusRequester.requestFocus() }
        }
    }

    // Slider position at 2 Hz, only while the controls are on screen and the track is playing.
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(playerState.isPlaying, track?.videoId, controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        while (isActive) {
            positionMs = manager.getCurrentPosition().coerceAtLeast(0L)
            durationMs = manager.getDuration().coerceAtLeast(0L)
            if (!playerState.isPlaying) break
            delay(500)
        }
    }

    fun commitScrub() {
        scrubController.commit()?.let { target ->
            manager.seekTo(target)
            positionMs = target
        }
        scrubUiState = scrubController.current
    }

    BackHandler {
        when {
            scrubController.current.isScrubbing -> {
                scrubController.cancel()
                scrubUiState = scrubController.current
            }

            panel != TvMusicPanel.NONE -> {
                panel = TvMusicPanel.NONE
            }

            controlsVisible -> {
                overlay.hide()
            }

            else -> {
                onCollapse()
            }
        }
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    val keyCode = event.nativeKeyEvent.keyCode
                    if (event.type == KeyEventType.KeyDown && !controlsVisible && visualizer != null &&
                        shownView == NowPlayingView.VISUALIZER
                    ) {
                        val forward = presetStepFor(keyCode)
                        if (forward != null) {
                            visualizer.onPresetStep(forward)
                            return@onPreviewKeyEvent true
                        }
                    }
                    if (event.type == KeyEventType.KeyDown && keyCode != KeyEvent.KEYCODE_BACK) {
                        val wasHidden = !controlsVisible
                        overlay.showTransport()
                        // A key only reveals hidden controls; the remote's media keys still act at once.
                        if (wasHidden && TvPlayerKeyMapper.map(keyCode) == null) return@onPreviewKeyEvent true
                    }
                    when (event.type) {
                        KeyEventType.KeyUp -> {
                            if (scrubController.current.isScrubbing &&
                                (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                            ) {
                                commitScrub()
                                true
                            } else {
                                false
                            }
                        }

                        KeyEventType.KeyDown -> {
                            val action =
                                TvPlayerKeyMapper.map(keyCode)
                                    ?: if (seekBarFocused) TvPlayerKeyMapper.mapDpadWhenSeekBarFocused(keyCode) else null
                            when (action) {
                                TvPlayerAction.TOGGLE_PLAYBACK,
                                TvPlayerAction.PLAY,
                                TvPlayerAction.PAUSE,
                                -> {
                                    manager.togglePlayPause()
                                }

                                TvPlayerAction.NEXT -> {
                                    manager.playNext()
                                }

                                TvPlayerAction.PREVIOUS -> {
                                    manager.playPrevious()
                                }

                                TvPlayerAction.SEEK_BACK -> {
                                    manager.seekTo((manager.getCurrentPosition() - 10_000L).coerceAtLeast(0L))
                                }

                                TvPlayerAction.SEEK_FORWARD -> {
                                    manager.seekTo(manager.getCurrentPosition() + 10_000L)
                                }

                                TvPlayerAction.SCRUB_BACK, TvPlayerAction.SCRUB_FORWARD -> {
                                    scrubUiState =
                                        scrubController.beginOrStep(
                                            direction = if (action == TvPlayerAction.SCRUB_FORWARD) 1 else -1,
                                            repeatCount = event.nativeKeyEvent.repeatCount,
                                            currentPositionMs = manager.getCurrentPosition(),
                                            durationMs = manager.getDuration(),
                                        )
                                }

                                TvPlayerAction.COMMIT_SCRUB -> {
                                    commitScrub()
                                }

                                else -> {
                                    return@onPreviewKeyEvent false
                                }
                            }
                            true
                        }

                        else -> {
                            false
                        }
                    }
                },
    ) {
        when (shownView) {
            NowPlayingView.VIDEO -> {
                TvMusicVideoSurface(player = manager.player, modifier = Modifier.fillMaxSize())
            }

            NowPlayingView.VISUALIZER -> {
                visualizer?.background?.invoke()
            }

            NowPlayingView.STATIC -> {
                TvNowPlayingArtwork(
                    artworkUrl = playbackArtwork?.forTrack(track?.videoId) ?: artworkUrl,
                    fallbackArtworkUrl = artworkUrl,
                )
            }
        }

        TvNowPlayingTrackCorner(
            current = handOver.shown,
            statusText = musicResolutionStatusText(resolution, track?.videoId),
            next = handOver.incoming,
            glitch = { handOver.glitch.value },
            contentColor = MaterialTheme.colorScheme.onSurface,
            maxWidth =
                if (panel == TvMusicPanel.NONE) {
                    screenWidth * CORNER_WIDTH_FRACTION
                } else {
                    screenWidth - dimens.sidePanelWidth - dimens.overscanHorizontal - PanelGap
                },
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(horizontal = dimens.overscanHorizontal, vertical = dimens.overscanVertical),
        )

        AnimatedVisibility(
            visible = controlsVisible,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = dimens.overscanHorizontal, vertical = dimens.overscanVertical),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            TvNowPlayingControls(
                state =
                    TvNowPlayingControlsState(
                        isPlaying = playerState.isPlaying,
                        isLiked = isLiked,
                        shuffleEnabled = shuffleEnabled,
                        repeatMode = repeatMode,
                        view = shownView,
                        nextView = nextView,
                        queueOpen = panel == TvMusicPanel.QUEUE,
                    ),
                actions =
                    TvNowPlayingControlsActions(
                        onSeekTo = { target ->
                            manager.seekTo(target)
                            positionMs = target
                        },
                        onSeekBarFocusChanged = { seekBarFocused = it },
                        onToggleShuffle = manager::toggleShuffle,
                        onPrevious = manager::playPrevious,
                        onTogglePlayPause = manager::togglePlayPause,
                        onNext = manager::playNext,
                        onToggleRepeat = manager::toggleRepeat,
                        onToggleLike = viewModel::toggleLike,
                        onNextView = {
                            manager.setVideoMode(nextView == NowPlayingView.VIDEO)
                            onViewChange(nextView)
                        },
                        onToggleQueue = {
                            panel = if (panel == TvMusicPanel.QUEUE) TvMusicPanel.NONE else TvMusicPanel.QUEUE
                        },
                    ),
                positionProvider = { scrubUiState.takeIf { it.isScrubbing }?.targetMs ?: positionMs },
                durationMs = durationMs,
                buttonColors = playerButtonColors,
                playPauseFocusRequester = playPauseFocusRequester,
                status = visualizer?.status?.takeIf { shownView == NowPlayingView.VISUALIZER },
            )
        }
        if (!controlsVisible) {
            Box(
                Modifier
                    .size(1.dp)
                    .focusRequester(hiddenFocusRequester)
                    .focusable(),
            )
        }

        TvMusicQueuePanel(
            visible = panel == TvMusicPanel.QUEUE,
            manager = manager,
            onPlayRadioTrack = viewModel::playRadioTrack,
            onClose = { panel = TvMusicPanel.NONE },
            tuning = tuning,
            onTune = viewModel.radioTuning::select,
        )
    }
}
