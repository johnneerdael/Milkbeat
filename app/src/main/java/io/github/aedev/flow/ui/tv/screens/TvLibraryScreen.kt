package io.github.aedev.flow.ui.tv.screens

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.catalog.MusicSource
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
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibraryPane
import io.github.aedev.flow.ui.tv.screens.folders.TvMusicFoldersContent
import io.github.aedev.flow.ui.tv.screens.library.TvLibraryMixedContent
import io.github.aedev.flow.ui.tv.screens.library.TvMergedPlaylistsPane
import io.github.aedev.flow.ui.tv.screens.music.TvMusicTabsState
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import io.github.aedev.flow.ui.tv.toTvMusicTrack
import io.github.aedev.flow.ui.tv.toTvVideo
import io.github.aedev.flow.ui.tv.tvWatchProgress
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.plugin.MetadataSurface

private enum class TvLibrarySection(
    @StringRes val titleRes: Int,
) {
    FOLDERS(R.string.music_folders_library),
    HISTORY(R.string.tv_library_history),
    LIKES(R.string.library_liked_songs),
    WATCH_LATER(R.string.tv_library_watch_later),
    PLAYLISTS(R.string.tv_library_playlists),
}

/**
 * Library hub mirroring mobile's mixed video + music library: every section
 * surfaces its played/saved songs as a music shelf above the video grid, and
 * Playlists covers both video and music playlists. Every signed-in music
 * provider with a library comes first, each with its own sections.
 */
@Composable
fun TvLibraryScreen(
    musicTabs: TvMusicTabsState,
    onVideoClick: (Video) -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlaylist: (String) -> Unit = {},
    onPlayTrack: (MusicTrack, List<MusicTrack>, String) -> Unit = { _, _, _ -> },
    onOpenMusicCollection: (String) -> Unit = {},
    onPlayMix: (MusicTrack) -> Unit = {},
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit = { _, _, _, _ -> },
    onConfigureFolders: () -> Unit = {},
    onOpenProviderCatalog: (String, EntityRef) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val historyRepository = remember { ViewHistory.getInstance(context.applicationContext) }
    val playlistRepository = remember { PlaylistRepository(context.applicationContext) }
    val history by historyRepository
        .getAllHistory()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val watchLater by playlistRepository
        .getWatchLaterVideosFlow()
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var selectedSection by rememberSaveable { mutableStateOf(TvLibrarySection.HISTORY) }
    var chosenProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var localPaneSelected by rememberSaveable { mutableStateOf(false) }
    val dimens = LocalTvDimens.current
    val accountTabs =
        musicTabs.tabs.filter { tab ->
            tab.source is MusicSource.Plugin && MetadataSurface.LIBRARY in tab.surfaces && (tab.signedIn || tab.expired)
        }
    // Signed-in listeners land on their first provider's library, as they did with a single provider.
    val shownProvider =
        accountTabs.firstOrNull { it.source.providerId == chosenProvider }
            ?: accountTabs.firstOrNull()?.takeIf { !localPaneSelected }

    TvScreenScaffold(
        title = null,
        modifier = modifier,
        subtitle = if (shownProvider?.expired == true) stringResource(R.string.tv_account_session_expired) else null,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            LazyRow(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .tvRowFocus(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal),
            ) {
                items(accountTabs, key = { "account-${it.source.key}" }) { tab ->
                    TvFilterChip(
                        label = tab.label.orEmpty(),
                        selected = shownProvider == tab,
                        onClick = {
                            localPaneSelected = false
                            chosenProvider = tab.source.providerId
                        },
                    )
                }
                val sections = TvLibrarySection.entries.filterNot { it == TvLibrarySection.LIKES }
                items(sections, key = TvLibrarySection::name) { section ->
                    TvFilterChip(
                        label = stringResource(section.titleRes),
                        selected =
                            shownProvider == null &&
                                (
                                    selectedSection == section ||
                                        (section == TvLibrarySection.PLAYLISTS && selectedSection == TvLibrarySection.LIKES)
                                ),
                        onClick = {
                            localPaneSelected = true
                            selectedSection = section
                        },
                    )
                }
            }

            if (shownProvider != null) {
                val pluginId = shownProvider.source.providerId
                TvAccountLibraryPane(
                    pluginId = pluginId,
                    callbacks =
                        TvAccountLibraryCallbacks(
                            onVideoClick = onVideoClick,
                            onOpenPlaylist = onOpenPlaylist,
                            onPlayMix = onPlayMix,
                            onPlayCollection = onPlayCollection,
                            onOpenCatalog = { onOpenProviderCatalog(pluginId, it) },
                        ),
                )
            } else {
                when (selectedSection) {
                    TvLibrarySection.FOLDERS -> {
                        TvMusicFoldersContent(onPlayTrack = onPlayTrack, onConfigure = onConfigureFolders)
                    }

                    TvLibrarySection.HISTORY -> {
                        TvLibraryMixedContent(
                            musicTracks = history.filter { it.isMusic }.map { it.toTvMusicTrack() },
                            musicSource = stringResource(TvLibrarySection.HISTORY.titleRes),
                            videos =
                                history
                                    .filterNot { it.isMusic }
                                    .map { entry -> entry.toTvVideo() to entry.tvWatchProgress() },
                            onVideoClick = onVideoClick,
                            onPlayTrack = onPlayTrack,
                        )
                    }

                    TvLibrarySection.WATCH_LATER -> {
                        TvLibraryMixedContent(
                            musicTracks = watchLater.filter { it.isMusic }.map(Video::toTvMusicTrack),
                            musicSource = stringResource(TvLibrarySection.WATCH_LATER.titleRes),
                            videos = watchLater.filterNot { it.isMusic }.map { it to null },
                            onVideoClick = onVideoClick,
                            onPlayTrack = onPlayTrack,
                        )
                    }

                    TvLibrarySection.PLAYLISTS, TvLibrarySection.LIKES -> {
                        TvMergedPlaylistsPane(
                            onOpenPlaylist = onOpenPlaylist,
                            onOpenMusicCollection = onOpenMusicCollection,
                            onOpenProviderCatalog = onOpenProviderCatalog,
                            onPlayTrack = onPlayTrack,
                            initiallyLiked = selectedSection == TvLibrarySection.LIKES,
                        )
                    }
                }
            }
        }
    }
}
