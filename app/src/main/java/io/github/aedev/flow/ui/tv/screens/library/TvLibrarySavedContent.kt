package io.github.aedev.flow.ui.tv.screens.library

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.PlaylistInfo
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvMediaRow
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvMusicCard
import io.github.aedev.flow.ui.tv.components.TvMusicCollectionCard
import io.github.aedev.flow.ui.tv.components.TvPlaylistCard
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.components.TvVideoCard
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibraryCallbacks
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibraryContent
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibrarySection
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibraryViewModel
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import io.github.aedev.flow.ui.tv.toTvMusicTrack
import io.github.aedev.flow.ui.tv.toTvVideo
import io.github.aedev.flow.ui.tv.tvWatchProgress
import nl.neerdael.milkbeat.catalog.EntityRef

private const val LIBRARY_GRID_COLUMNS = 3

@Composable
internal fun TvLibraryMixedContent(
    musicTracks: List<MusicTrack>,
    musicSource: String,
    videos: List<Pair<Video, Float?>>,
    onVideoClick: (Video) -> Unit,
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit,
) {
    val dimens = LocalTvDimens.current
    if (musicTracks.isEmpty() && videos.isEmpty()) {
        TvMessageState(
            title = stringResource(R.string.tv_library_empty),
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimens.overscanHorizontal),
        )
        return
    }
    ProvideTvColumnPivot {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val cardWidth =
                (
                    maxWidth - dimens.overscanHorizontal * 2 -
                        dimens.itemSpacing * (LIBRARY_GRID_COLUMNS - 1)
                ) / LIBRARY_GRID_COLUMNS
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                contentPadding = PaddingValues(bottom = dimens.overscanVertical),
            ) {
                if (musicTracks.isNotEmpty()) {
                    item(key = "library-music") {
                        TvMediaRow(
                            items = musicTracks,
                            key = MusicTrack::videoId,
                            title = stringResource(R.string.nav_music),
                        ) { track ->
                            TvMusicCard(
                                track = track,
                                onClick = { onPlayTrack(track, musicTracks, musicSource) },
                            )
                        }
                    }
                }
                items(
                    items = videos.chunked(LIBRARY_GRID_COLUMNS),
                    key = { rowItems -> rowItems.first().first.id },
                ) { rowItems ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = dimens.overscanHorizontal),
                        horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
                    ) {
                        rowItems.forEach { (video, progress) ->
                            TvVideoCard(
                                video = video,
                                onClick = { onVideoClick(video) },
                                watchProgress = progress,
                                modifier = Modifier.width(cardWidth),
                            )
                        }
                    }
                }
            }
        }
    }
}
