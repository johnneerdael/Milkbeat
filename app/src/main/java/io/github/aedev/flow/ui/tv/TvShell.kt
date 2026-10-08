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
import androidx.compose.runtime.rememberUpdatedState
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
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.tv.components.TvNavRail
import io.github.aedev.flow.ui.tv.components.TvNowPlayingStrip
import io.github.aedev.flow.ui.tv.components.tvRailItems
import io.github.aedev.flow.ui.tv.navigation.TvBackAction
import io.github.aedev.flow.ui.tv.navigation.TvBackModel
import io.github.aedev.flow.ui.tv.navigation.TvDestination
import io.github.aedev.flow.ui.tv.navigation.TvMusicTabsState
import io.github.aedev.flow.ui.tv.navigation.TvNavHost
import io.github.aedev.flow.ui.tv.navigation.TvRoutes
import io.github.aedev.flow.ui.tv.navigation.TvTab
import io.github.aedev.flow.ui.tv.navigation.prunedTabHistory
import io.github.aedev.flow.ui.tv.navigation.shownRailTab
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.delay
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
    musicTabs: TvMusicTabsState = TvMusicTabsState(),
    onSelectMusic: (MusicSource) -> Unit = {},
) {
    val dimens = LocalTvDimens.current
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isOnDetailRoute = currentRoute != null && TvDestination.entries.none { it.route == currentRoute }
    val routeTab: TvTab? =
        when {
            currentRoute == null || currentRoute == TvDestination.MUSIC.route -> TvTab.Music(musicTabs.selected)
            isOnDetailRoute -> null
            else -> TvTab.Fixed(TvDestination.fromRoute(currentRoute))
        }
    // A detail page keeps the tab it was opened from highlighted.
    var detailOwner by remember { mutableStateOf<TvTab>(TvTab.Music(null)) }
    LaunchedEffect(routeTab) { routeTab?.let { detailOwner = it } }
    val railItems = tvRailItems(musicTabs, badged)
    val railTabs = railItems.map { it.tab }
    val currentTab: TvTab =
        shownRailTab(
            routeTab = routeTab,
            settingsDetail = currentRoute == TvRoutes.MUSIC_FOLDERS_SETTINGS,
            detailOwner = detailOwner,
            railTabs = railTabs,
            music = TvTab.Music(musicTabs.selected),
        )
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
    // focus would open the rail; it waits for the content to take focus instead. A page that never
    // offers anything to focus must not lock the remote out, so the wait is bounded.
    var railAcceptsFocus by remember { mutableStateOf(false) }
    LaunchedEffect(railAcceptsFocus) {
        if (railAcceptsFocus) return@LaunchedEffect
        delay(CONTENT_FOCUS_WAIT_MS)
        railAcceptsFocus = true
    }

    val tabHistory = remember { mutableStateListOf<TvTab>() }
    // The rail keeps the selectTab reference of the composition that built it, so the tab shown is read
    // at the press rather than captured then.
    // A provider signed out, disabled or removed leaves Back history, so Back never lands on a tab that is gone.
    LaunchedEffect(railTabs) {
        val kept = prunedTabHistory(tabHistory, railTabs)
        if (kept.size != tabHistory.size) {
            tabHistory.clear()
            tabHistory.addAll(kept)
        }
    }
    val shownTab by rememberUpdatedState(currentTab)
    val onDetailRoute by rememberUpdatedState(isOnDetailRoute)
    val selectMusic by rememberUpdatedState(onSelectMusic)

    fun navigateToTab(tab: TvTab) {
        if (tab is TvTab.Music) tab.source?.let(selectMusic)
        navController.navigate(tab.destination.route) {
            popUpTo(TvDestination.start.route) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun selectTab(tab: TvTab) {
        val current = shownTab
        if (tab != current) {
            tabHistory.remove(tab)
            if (!onDetailRoute) {
                tabHistory.remove(current)
                tabHistory.add(current)
            }
        }
        navigateToTab(tab)
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
                    .onFocusChanged { if (it.hasFocus) railAcceptsFocus = true },
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TvNavHost(
                    navController = navController,
                    onPlayTrack = onPlayTrack,
                    onPlayMix = onPlayMix,
                    onPlayCollection = onPlayCollection,
                    onPlayVideo = onPlayVideo,
                    onPlayPlaylist = onPlayPlaylist,
                    onOpenPlugins = { selectTab(TvTab.Fixed(TvDestination.SETTINGS)) },
                    musicTabs = musicTabs,
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
            items = railItems,
            selected = currentTab,
            onSelected = ::selectTab,
            onFocusChanged = { railHasFocus = it },
            selectedFocusRequester = railFocusRequester,
            acceptsEnteringFocus = railAcceptsFocus,
            modifier =
                Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight(),
        )

        val backAction =
            TvBackModel.resolve(
                isOnDetailRoute = isOnDetailRoute,
                hasTabHistory = tabHistory.isNotEmpty(),
                onStartTab = currentTab is TvTab.Music,
                railHasFocus = railHasFocus,
            )
        BackHandler(enabled = backAction != TvBackAction.EXIT) {
            when (backAction) {
                TvBackAction.POP_DETAIL -> navController.popBackStack()
                TvBackAction.POP_TAB -> navigateToTab(tabHistory.removeAt(tabHistory.lastIndex))
                TvBackAction.GO_START -> navigateToTab(TvTab.Music(musicTabs.selected))
                TvBackAction.FOCUS_RAIL -> runCatching { railFocusRequester.requestFocus() }
                TvBackAction.EXIT -> Unit
            }
        }
    }
}
