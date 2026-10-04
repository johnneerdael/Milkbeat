package io.github.aedev.flow.ui.tv

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.tv.components.TvNavRail
import io.github.aedev.flow.ui.tv.components.TvNowPlayingStrip
import io.github.aedev.flow.ui.tv.navigation.TvBackAction
import io.github.aedev.flow.ui.tv.navigation.TvBackModel
import io.github.aedev.flow.ui.tv.navigation.TvDestination
import io.github.aedev.flow.ui.tv.navigation.TvNavHost
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val CONTENT_FOCUS_WAIT_MS = 3_000L

/**
 * TV browse shell: content + optional music strip laid out against the collapsed
 * rail width; the rail overlays on top so its focus expansion never reflows content.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun TvShell(
    navController: NavHostController,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit,
    onPlayVideo: (Video) -> Unit,
    onPlayPlaylist: (List<Video>, String) -> Unit,
    activeMusicTrack: MusicTrack?,
    onExpandMusic: () -> Unit,
    onDismissMusic: () -> Unit,
    modifier: Modifier = Modifier,
    focusMusicStrip: Boolean = false,
    onMusicStripFocused: () -> Unit = {},
    badged: TvDestination? = null,
) {
    val dimens = LocalTvDimens.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentTab = TvDestination.fromRoute(currentRoute)
    val isOnDetailRoute = currentRoute != null && TvDestination.entries.none { it.route == currentRoute }
    var railHasFocus by remember { mutableStateOf(false) }
    val railFocusRequester = remember { FocusRequester() }
    val musicStripFocusRequester = remember { FocusRequester() }
    val contentFocusRequester = remember { FocusRequester() }
    var contentFocusRequests by remember { mutableIntStateOf(0) }

    // A rail press, even on the tab already shown, hands focus to the page's first item so the rail
    // closes and the remote is already on the content. A new tab cross-fades in and may still be
    // loading, so this waits for the old page to leave and then for something to focus.
    LaunchedEffect(contentFocusRequests) {
        if (contentFocusRequests == 0) return@LaunchedEffect
        withTimeoutOrNull(CONTENT_FOCUS_WAIT_MS) {
            navController.visibleEntries.first { it.size <= 1 }
            do {
                withFrameNanos {}
            } while (!contentFocusRequester.requestFocus(FocusDirection.Enter))
        }
    }

    // The shell is rebuilt when now-playing closes, with nothing focused, so every key went nowhere.
    // Focus returns to the strip the user came from; up leads back into the page.
    LaunchedEffect(focusMusicStrip, activeMusicTrack != null) {
        if (!focusMusicStrip || activeMusicTrack == null) return@LaunchedEffect
        withFrameNanos {}
        runCatching { musicStripFocusRequester.requestFocus() }
        onMusicStripFocused()
    }

    // The first screen has nothing focusable but the rail while it loads, so the window's initial
    // focus would open the rail; it waits for the content to take focus instead.
    var contentFocusedOnce by remember { mutableStateOf(false) }

    val tabHistory = remember { mutableStateListOf<TvDestination>() }

    fun navigateToTab(destination: TvDestination) {
        navController.navigate(destination.route) {
            popUpTo(TvDestination.start.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun selectTab(destination: TvDestination) {
        if (destination != currentTab) {
            tabHistory.remove(destination)
            if (!isOnDetailRoute) {
                tabHistory.remove(currentTab)
                tabHistory.add(currentTab)
            }
        }
        navigateToTab(destination)
        contentFocusRequests++
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(start = dimens.railCollapsedWidth)
                    .focusRequester(contentFocusRequester)
                    .focusProperties {
                        @OptIn(ExperimentalComposeUiApi::class)
                        exit = { direction ->
                            if (direction == FocusDirection.Left) {
                                railFocusRequester
                            } else {
                                FocusRequester.Default
                            }
                        }
                    }.focusGroup()
                    .onFocusChanged { if (it.hasFocus) contentFocusedOnce = true },
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TvNavHost(
                    navController = navController,
                    onPlayTrack = onPlayTrack,
                    onPlayMix = onPlayMix,
                    onPlayCollection = onPlayCollection,
                    onPlayVideo = onPlayVideo,
                    onPlayPlaylist = onPlayPlaylist,
                    onOpenPlugins = { selectTab(TvDestination.SETTINGS) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            activeMusicTrack?.let { track ->
                TvNowPlayingStrip(
                    track = track,
                    onExpand = onExpandMusic,
                    onDismiss = onDismissMusic,
                    focusRequester = musicStripFocusRequester,
                )
            }
        }
        TvNavRail(
            selected = currentTab,
            onSelected = ::selectTab,
            onFocusChanged = { railHasFocus = it },
            selectedFocusRequester = railFocusRequester,
            acceptsEnteringFocus = contentFocusedOnce,
            badged = badged,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(),
        )

        val backAction =
            TvBackModel.resolve(
                isOnDetailRoute = isOnDetailRoute,
                hasTabHistory = tabHistory.isNotEmpty(),
                currentTab = currentTab,
                railHasFocus = railHasFocus,
            )
        BackHandler(enabled = backAction != TvBackAction.EXIT) {
            when (backAction) {
                TvBackAction.POP_DETAIL -> navController.popBackStack()
                TvBackAction.POP_TAB -> navigateToTab(tabHistory.removeAt(tabHistory.lastIndex))
                TvBackAction.GO_START -> navigateToTab(TvDestination.start)
                TvBackAction.FOCUS_RAIL -> runCatching { railFocusRequester.requestFocus() }
                TvBackAction.EXIT -> Unit
            }
        }
    }
}
