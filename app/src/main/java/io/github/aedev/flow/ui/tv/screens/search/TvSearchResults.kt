package io.github.aedev.flow.ui.tv.screens.search

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.catalog.catalogBlocks
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PageBlock

/** How many list items before the end the next page is asked for. */
private const val LOAD_MORE_AHEAD = 3

/**
 * What picking a result does: a song plays with its own mix, as YouTube Music does from search, and
 * anything else opens as a catalog page. Acting on a result saves the query first.
 */
@Composable
internal fun rememberTvSearchActions(
    viewModel: TvSearchViewModel,
    beforeOpen: () -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onOpenCatalog: (EntityRef) -> Unit,
): TvCatalogActions {
    val saveQuery by rememberUpdatedState(beforeOpen)
    val playMix by rememberUpdatedState(onPlayMix)
    val openCatalog by rememberUpdatedState(onOpenCatalog)
    return remember(viewModel) {
        val playSong: (MusicTrack) -> Unit = { track ->
            saveQuery()
            playMix(track)
        }
        TvCatalogActions(
            trackFor = viewModel::track,
            onPlayMix = playSong,
            onPlayList = { track, _, _, _ -> playSong(track) },
            onOpen = { entity ->
                saveQuery()
                openCatalog(entity)
            },
            onShowAllFilter = viewModel::showAll,
        )
    }
}

/** The results of the chip on screen: its page as catalog blocks, or why there is none. */
@Composable
internal fun TvSearchResultsPane(
    query: String,
    results: TvSearchResults,
    actions: TvCatalogActions,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val blocks = results.blocks
    when {
        query.isBlank() -> {
            TvMessageState(title = stringResource(R.string.tv_search_empty), modifier = modifier)
        }

        results.noPlugin -> {
            TvMessageState(
                title = stringResource(R.string.tv_search_no_music_plugin),
                message = stringResource(R.string.tv_search_no_plugin_message),
                modifier = modifier,
            )
        }

        (results.isLoading || !results.loaded) && blocks.isEmpty() && results.error == null -> {
            TvLoadingState(modifier = modifier)
        }

        results.error != null && blocks.isEmpty() -> {
            TvMessageState(title = stringResource(R.string.tv_error_loading), message = results.error, modifier = modifier)
        }

        blocks.isEmpty() -> {
            TvMessageState(title = stringResource(R.string.tv_search_no_music_results), modifier = modifier)
        }

        else -> {
            TvSearchResultList(blocks, results.isLoadingMore, results.nextCursor != null, actions, onLoadMore, modifier)
        }
    }
}

@Composable
private fun TvSearchResultList(
    blocks: List<PageBlock>,
    isLoadingMore: Boolean,
    hasMore: Boolean,
    actions: TvCatalogActions,
    onLoadMore: () -> Unit,
    modifier: Modifier,
) {
    val dimens = LocalTvDimens.current
    val listState = rememberLazyListState()
    val gridId = remember(blocks) { blocks.resultsGridId() }
    val loadMore by rememberUpdatedState(onLoadMore)
    if (hasMore) {
        LaunchedEffect(listState, blocks) {
            snapshotFlow { listState.nearsEnd() }
                .distinctUntilChanged()
                .filter { it }
                .collect { loadMore() }
        }
    }
    ProvideTvColumnPivot {
        LazyColumn(
            state = listState,
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = dimens.overscanVertical),
        ) {
            catalogBlocks(blocks, actions, horizontalPadding = dimens.overscanHorizontal, gridBlockId = gridId)
            if (isLoadingMore) {
                item(key = "search-loading-more") { TvShimmerRow(showHeader = false) }
            }
        }
    }
}

private fun LazyListState.nearsEnd(): Boolean {
    val info = layoutInfo
    val last = info.visibleItemsInfo.lastOrNull()?.index ?: return false
    return info.totalItemsCount > 0 && last >= info.totalItemsCount - 1 - LOAD_MORE_AHEAD
}
