package io.github.aedev.flow.ui.tv.screens.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.catalog.TvCatalogFilterChips
import io.github.aedev.flow.ui.tv.catalog.catalogBlocks
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvShimmerRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.EntityRef

/** The local library's home as a Library section: its genre chips above the shelves the Music home would show. */
@Composable
internal fun TvLocalLibraryContent(
    viewModel: TvLocalLibraryViewModel,
    onPlayMix: (MusicTrack) -> Unit,
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit,
    onOpen: (EntityRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val dimens = LocalTvDimens.current
    val playMix by rememberUpdatedState(onPlayMix)
    val playCollection by rememberUpdatedState(onPlayCollection)
    val open by rememberUpdatedState(onOpen)
    val actions =
        remember(viewModel) {
            TvCatalogActions(
                trackFor = viewModel::track,
                onPlayMix = { playMix(it) },
                onPlayList = { track, queue, source, radioPlaylistId -> playCollection(track, queue, source, radioPlaylistId) },
                onOpen = { open(it) },
            )
        }
    Column(modifier.fillMaxSize()) {
        if (state.filters.isNotEmpty()) TvCatalogFilterChips(state.filters, state.selectedFilterId, viewModel::selectFilter)
        ProvideTvColumnPivot {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = dimens.overscanVertical),
            ) {
                when {
                    state.isLoading && state.blocks.isEmpty() -> {
                        item(key = "local-loading") { TvShimmerRow() }
                    }

                    state.error != null && state.blocks.isEmpty() -> {
                        item(key = "local-error") {
                            TvMessageState(title = stringResource(R.string.tv_error_loading), message = state.error)
                        }
                    }

                    else -> {
                        catalogBlocks(state.blocks, actions, horizontalPadding = dimens.overscanHorizontal)
                    }
                }
            }
        }
    }
}
