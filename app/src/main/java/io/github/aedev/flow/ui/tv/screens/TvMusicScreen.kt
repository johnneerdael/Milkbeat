package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.screens.folders.LibraryScanViewModel
import io.github.aedev.flow.ui.screens.music.MusicHomeFeedViewModel
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.catalog.TvCatalogFilterChips
import io.github.aedev.flow.ui.tv.catalog.catalogBlocks
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLibraryScanStatus
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.EntityRef

/**
 * The music tabs: the shown [source]'s home page — its filters, then every block in the order it is
 * served. Each source keeps its own feed and scroll position while another tab shows; until [ready],
 * the tab to show is not known yet and the page shows it is loading.
 */
@Composable
fun TvMusicScreen(
    source: MusicSource?,
    ready: Boolean,
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onOpen: (EntityRef, MusicSource) -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenMusicFolders: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Above the loading return, so a moment of not knowing the tab keeps every tab's saved scroll and focus.
    val states = rememberSaveableStateHolder()
    if (!ready) {
        TvScreenScaffold(title = null, modifier = modifier) { TvShimmerRow() }
        return
    }
    states.SaveableStateProvider(source?.key ?: NO_SOURCE_KEY) {
        val viewModel =
            hiltViewModel<MusicHomeFeedViewModel, MusicHomeFeedViewModel.Factory>(
                key = "music-feed:${source?.key ?: NO_SOURCE_KEY}",
                creationCallback = { factory -> factory.create(source) },
            )
        TvMusicFeed(
            viewModel = viewModel,
            onPlayCollection = onPlayCollection,
            onPlayMix = onPlayMix,
            onOpen = { ref -> source?.let { onOpen(ref, it) } },
            onOpenPlugins = onOpenPlugins,
            onOpenMusicFolders = onOpenMusicFolders,
            modifier = modifier,
        )
    }
}

private const val NO_SOURCE_KEY = "none"

@Composable
private fun TvMusicFeed(
    viewModel: MusicHomeFeedViewModel,
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onOpen: (EntityRef) -> Unit,
    onOpenPlugins: () -> Unit,
    onOpenMusicFolders: () -> Unit,
    modifier: Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val accountExpired by viewModel.isAccountExpired.collectAsStateWithLifecycle()
    val dimens = LocalTvDimens.current
    LaunchedEffect(viewModel) { viewModel.load() }
    val firstShelfFocus = remember { FocusRequester() }
    // The app opens on this tab with only the rail focusable until the feed arrives; move into the
    // content once, so a later return to the tab from the rail keeps focus where the user put it.
    var openedOnContent by rememberSaveable { mutableStateOf(false) }
    val blocks = state.blocks
    val hasShelves = blocks.isNotEmpty()
    val playMix by rememberUpdatedState(onPlayMix)
    val playCollection by rememberUpdatedState(onPlayCollection)
    val open by rememberUpdatedState(onOpen)
    val actions =
        remember(viewModel) {
            TvCatalogActions(
                trackFor = viewModel::track,
                onPlayMix = { playMix(it) },
                onPlayList = { track, queue, source, radioPlaylistId ->
                    playCollection(track, queue, source, viewModel.radioSeed(radioPlaylistId))
                },
                onOpen = { open(it) },
            )
        }
    // An empty library's scan button, or a failed or empty home's retry, takes focus once; the shelves take
    // it again when they arrive. Without it the rail is all there is, and it waits for the content first.
    var openedOnScan by rememberSaveable { mutableStateOf(false) }
    var openedOnRetry by rememberSaveable { mutableStateOf(false) }
    val scanOnly = state.libraryEmpty && !hasShelves
    val retryOnly =
        !hasShelves && !state.needsPlugin && !state.libraryEmpty &&
            (state.error != null || (!state.isLoading && !state.isLoadingMore))
    LaunchedEffect(hasShelves, state.needsPlugin, scanOnly, retryOnly) {
        val opened = openedOnContent || (scanOnly && openedOnScan) || (retryOnly && openedOnRetry)
        if ((hasShelves || state.needsPlugin || scanOnly || retryOnly) && !opened) {
            withFrameNanos { }
            runCatching { firstShelfFocus.requestFocus() }
            when {
                scanOnly -> openedOnScan = true
                retryOnly -> openedOnRetry = true
                else -> openedOnContent = true
            }
        }
    }
    // The retry the user pressed takes the focus back if the page comes back without shelves again.
    val retry: () -> Unit = {
        if (!state.isLoading) {
            openedOnRetry = false
            viewModel.load(force = true)
        }
    }

    TvScreenScaffold(
        title = null,
        modifier = modifier,
        subtitle = if (accountExpired) stringResource(R.string.tv_account_session_expired) else null,
    ) {
        // The moods stay pinned above the shelves, as in YouTube Music: in the scrolling list, pivoting a
        // shelf into place pushed them off the top.
        Column(Modifier.fillMaxSize()) {
            if (state.filters.isNotEmpty()) {
                TvCatalogFilterChips(state.filters, state.selectedFilterId, viewModel::selectFilter)
            }
            ProvideTvColumnPivot {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = dimens.overscanVertical),
                ) {
                    when {
                        state.needsPlugin && blocks.isEmpty() -> {
                            item(key = "music-needs-plugin") {
                                Column(
                                    modifier = Modifier.fillMaxWidth().padding(horizontal = dimens.overscanHorizontal),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    TvMessageState(
                                        title = stringResource(R.string.tv_plugins_empty_home),
                                        message = stringResource(R.string.tv_plugins_empty_home_message),
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        TvButton(
                                            text = stringResource(R.string.tv_plugins_add),
                                            onClick = onOpenPlugins,
                                            modifier = Modifier.focusRequester(firstShelfFocus),
                                        )
                                        TvButton(text = stringResource(R.string.music_folders_title), onClick = onOpenMusicFolders)
                                    }
                                }
                            }
                        }

                        state.libraryEmpty && blocks.isEmpty() -> {
                            item(key = "music-library-empty") {
                                val scanViewModel: LibraryScanViewModel = hiltViewModel()
                                val scan by scanViewModel.state.collectAsStateWithLifecycle()
                                TvLibraryScanStatus(
                                    scan = scan,
                                    onRescan = scanViewModel::rescan,
                                    modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
                                    actionModifier = Modifier.focusRequester(firstShelfFocus),
                                    idleTitle = R.string.local_library_empty,
                                )
                            }
                        }

                        // Kept while a retry runs, so the focus on its button stays put.
                        state.error != null && blocks.isEmpty() -> {
                            item(key = "music-error") {
                                TvMusicRetryState(
                                    title = stringResource(R.string.tv_error_loading),
                                    message = state.error,
                                    retrying = state.isLoading,
                                    onRetry = retry,
                                    actionModifier = Modifier.focusRequester(firstShelfFocus),
                                )
                            }
                        }

                        state.isLoading && blocks.isEmpty() -> {
                            item(key = "music-loading") { TvShimmerRow() }
                        }

                        blocks.isEmpty() && !state.isLoadingMore -> {
                            item(key = "music-empty") {
                                TvMusicRetryState(
                                    title = stringResource(R.string.tv_music_empty),
                                    retrying = false,
                                    onRetry = retry,
                                    actionModifier = Modifier.focusRequester(firstShelfFocus),
                                )
                            }
                        }

                        else -> {
                            catalogBlocks(
                                blocks = blocks,
                                actions = actions,
                                horizontalPadding = dimens.overscanHorizontal,
                                firstBlockModifier = Modifier.focusRequester(firstShelfFocus).focusGroup(),
                            )
                            if (state.isLoadingMore) {
                                item(key = "music-loading-more") { TvShimmerRow() }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TvMusicRetryState(
    title: String,
    retrying: Boolean,
    onRetry: () -> Unit,
    actionModifier: Modifier,
    message: String? = null,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = LocalTvDimens.current.overscanHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TvMessageState(title = title, message = message)
        TvButton(
            text = stringResource(if (retrying) R.string.tv_music_retrying else R.string.retry),
            onClick = onRetry,
            modifier = actionModifier,
        )
    }
}
