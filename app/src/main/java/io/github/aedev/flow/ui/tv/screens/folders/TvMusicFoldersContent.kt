package io.github.aedev.flow.ui.tv.screens.folders

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.screens.folders.MusicFoldersViewModel
import io.github.aedev.flow.ui.screens.folders.labelRes
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvMusicTrackRow
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvAcceleratedDpad
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun TvMusicFoldersContent(
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicFoldersViewModel = hiltViewModel(),
) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val state by viewModel.browser.collectAsStateWithLifecycle()
    val source = state.source
    val title = state.stack.lastOrNull()?.name ?: stringResource(R.string.music_folders_library)
    val tracks = state.tracks
    val listState = rememberLazyListState()
    LaunchedEffect(source?.id, state.stack.lastOrNull()?.path, state.entries) {
        if (source == null) return@LaunchedEffect
        @OptIn(kotlinx.coroutines.FlowPreview::class)
        snapshotFlow {
            listState.layoutInfo.visibleItemsInfo
                .map { it.key.toString() }
                .toSet()
        }.distinctUntilChanged()
            .debounce(150)
            .collectLatest(viewModel::enrichVisible)
    }
    val dimens = LocalTvDimens.current
    LaunchedEffect(folders) { viewModel.sourcesChanged(folders) }
    DisposableEffect(viewModel) {
        viewModel.resumeBrowsing()
        onDispose { viewModel.stopBrowsing() }
    }
    BackHandler(source != null) { viewModel.back() }
    ProvideTvColumnPivot {
        Column(
            modifier =
                modifier
                    .fillMaxSize()
                    .padding(
                        horizontal = dimens.overscanHorizontal,
                    ).tvInitialFocus(source?.id, state.stack.lastOrNull()?.path)
                    .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvSectionHeader(title)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (source != null) {
                    TvButton(
                        stringResource(
                            if (state.stack.size >
                                1
                            ) {
                                R.string.music_folders_up
                            } else {
                                R.string.music_folders_back
                            },
                        ),
                        viewModel::back,
                    )
                    TvButton(stringResource(R.string.music_folders_refresh), viewModel::refresh, enabled = !state.loading)
                    if (tracks.isNotEmpty()) {
                        TvButton(
                            stringResource(R.string.music_folders_play_all),
                            {
                                viewModel.stopMetadata()
                                onPlayTrack(tracks.first(), tracks, title)
                            },
                        )
                    }
                } else {
                    TvButton(stringResource(R.string.music_folders_title), onConfigure)
                }
            }
            when {
                source == null && folders.isEmpty() -> {
                    TvMessageState(
                        stringResource(R.string.music_folders_empty),
                        stringResource(R.string.music_folders_empty_hint),
                        Modifier.weight(1f),
                    )
                }

                state.loading -> {
                    TvLoadingState(Modifier.weight(1f))
                }

                state.failed -> {
                    TvMessageState(stringResource(R.string.music_folders_browse_failed), modifier = Modifier.weight(1f))
                }

                source != null && state.entries.isEmpty() -> {
                    TvMessageState(stringResource(R.string.music_folders_directory_empty), modifier = Modifier.weight(1f))
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.weight(1f).tvAcceleratedDpad(),
                        contentPadding = PaddingValues(bottom = dimens.overscanVertical),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (source == null) {
                            items(folders, key = { it.id }) { folder ->
                                TvNavRow(
                                    folder.name,
                                    { viewModel.openSource(folder) },
                                    leadingIcon = Icons.Outlined.Folder,
                                    supportingText = stringResource(folder.kind.labelRes()),
                                )
                            }
                        } else {
                            items(state.entries, key = { it.location }) { entry ->
                                if (entry.isDirectory) {
                                    TvNavRow(entry.name, { viewModel.openDirectory(entry) }, leadingIcon = Icons.Outlined.Folder)
                                } else {
                                    val track = state.metadataTracks[entry.location] ?: remember(source, entry) { entry.track(source) }
                                    TvMusicTrackRow(track, {
                                        viewModel.stopMetadata()
                                        onPlayTrack(track, tracks, title)
                                    })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
