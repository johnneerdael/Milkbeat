package io.github.aedev.flow.ui.tv.screens

import android.view.KeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.ui.screens.music.CatalogPageViewModel
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.catalog.TvCatalogEntityHeader
import io.github.aedev.flow.ui.tv.catalog.TvCatalogFollow
import io.github.aedev.flow.ui.tv.catalog.TvCatalogTableLayout
import io.github.aedev.flow.ui.tv.catalog.catalogBlocks
import io.github.aedev.flow.ui.tv.catalog.catalogIndexOf
import io.github.aedev.flow.ui.tv.catalog.catalogPlayingRow
import io.github.aedev.flow.ui.tv.catalog.isTrackTable
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvAcceleratedDpad
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.PageBlock

/**
 * An artist, album or playlist page of the music provider. An album or playlist keeps its cover and
 * details beside its tracks; once the tracks have scrolled by, the shelves below take the full width.
 */
@Composable
fun TvCatalogPageScreen(
    onPlayMix: (MusicTrack) -> Unit,
    onPlayCollection: (MusicTrack, List<MusicTrack>, String, String?) -> Unit,
    onOpen: (EntityRef) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogPageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val mirror by viewModel.mirror.collectAsStateWithLifecycle()
    val following by viewModel.following.collectAsStateWithLifecycle()
    val dimens = LocalTvDimens.current
    val sourceIdentity by viewModel.sourceIdentity.collectAsStateWithLifecycle(initialValue = "")
    LaunchedEffect(viewModel, sourceIdentity) {
        if (sourceIdentity.isNotEmpty()) viewModel.load(sourceIdentity)
    }
    val playMix by rememberUpdatedState(onPlayMix)
    val playCollection by rememberUpdatedState(onPlayCollection)
    val open by rememberUpdatedState(onOpen)
    val actions =
        remember(viewModel) {
            TvCatalogActions(
                trackFor = viewModel::track,
                onPlayMix = { playMix(it) },
                onPlayList = {
                    track,
                    queue,
                    source,
                    radioPlaylistId,
                    ->
                    playCollection(track, queue, source, viewModel.radioSeed(radioPlaylistId))
                },
                onOpen = { open(it) },
                follow = TvCatalogFollow(isFollowing = { following }, toggle = viewModel::toggleFollow),
            )
        }
    val playingTrack by EnhancedMusicPlayerManager.currentTrack.collectAsStateWithLifecycle()
    val playingCollection by EnhancedMusicPlayerManager.queueCollection.collectAsStateWithLifecycle()
    val playingSource by EnhancedMusicPlayerManager.playingFrom.collectAsStateWithLifecycle()
    val blocks = state.blocks
    when {
        state.isLoading && blocks.isEmpty() -> {
            TvLoadingState(modifier)
        }

        state.error != null && blocks.isEmpty() -> {
            TvMessageState(title = stringResource(R.string.tv_error_loading), message = state.error, modifier = modifier)
        }

        else -> {
            val cover = (blocks.firstOrNull() as? EntityHeader)?.takeIf { it.style == HeaderStyle.COVER }
            ProvideTvColumnPivot {
                if (cover != null) {
                    val currentCollection = cover.isPlaying(blocks, playingCollection, playingSource)
                    key(cover.entity) {
                        CoverPage(
                            cover,
                            blocks,
                            actions,
                            modifier,
                            playingTrack?.videoId.takeIf {
                                currentCollection
                            },
                            playingTrack?.sourcePosition.takeIf { currentCollection },
                        ) {
                            TvPlaylistMirrorStatus(mirror, viewModel::retryMirror)
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = dimens.overscanVertical),
                    ) {
                        catalogBlocks(blocks, actions, horizontalPadding = dimens.overscanHorizontal)
                    }
                }
            }
        }
    }
}

/**
 * Whether the queue was started from this page. Play and the track rows seed the queue with the page's
 * track collection, which for an album can be a playlist distinct from the album itself.
 */
internal fun EntityHeader.isPlaying(
    blocks: List<PageBlock>,
    playingCollection: String?,
    playingSource: String,
): Boolean {
    val collectionId =
        playingCollection?.let { ProviderEntityReference.decode(it)?.entity?.providerId ?: it }
            ?: return playingSource == title
    val queueId = (tracks ?: (blocks.firstOrNull { it.isTrackTable } as CollectionBlock?)?.showAll)?.providerId
    return collectionId == entity.providerId || collectionId == queueId
}

/**
 * The cover pane sits over the left of one scrolling list whose track rows are inset beside it. It
 * rides up with the last track, so the full-width shelves below never pass under it. From the pane's
 * buttons, Right goes to the first track and Down to the first shelf, wherever the list is scrolled.
 */
@Composable
internal fun CoverPage(
    cover: EntityHeader,
    blocks: List<PageBlock>,
    actions: TvCatalogActions,
    modifier: Modifier,
    playingTrackId: String? = null,
    playingTrackPosition: Int? = null,
    status: @Composable () -> Unit = {},
) {
    val dimens = LocalTvDimens.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val body = remember(blocks) { blocks.drop(1) }
    val openingTrackId = remember(cover.entity) { playingTrackId }
    val openingTrackPosition = remember(cover.entity) { playingTrackPosition }
    val playingRow = body.catalogPlayingRow(openingTrackPosition, openingTrackId)
    val playingFocus = remember { FocusRequester() }
    val paneFocus = remember { FocusRequester() }
    val tables =
        remember(openingTrackId, openingTrackPosition) {
            TvCatalogTableLayout(
                dimens.coverPaneWidth + dimens.rowSpacing,
                FocusRequester(),
                FocusRequester(),
                openingTrackId,
                playingFocus,
                paneFocus,
                openingTrackPosition,
            )
        }
    var positioned by remember(cover.entity) { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(playingRow) {
        if (!positioned && playingRow != null) {
            listState.scrollToItem(playingRow)
            withFrameNanos { }
            playingFocus.requestFocus()
            positioned = true
        }
    }
    val firstTrackIndex = body.firstOrNull { it.isTrackTable }?.let(body::catalogIndexOf)
    val lastTrackIndex = (body.lastOrNull { it.isTrackTable } as CollectionBlock?)?.let { body.catalogIndexOf(it) + it.items.size - 1 }
    val firstShelfIndex = body.firstOrNull { it is CollectionBlock && !it.isTrackTable }?.let(body::catalogIndexOf)
    var paneHeight by remember { mutableIntStateOf(0) }

    fun focusItem(
        index: Int?,
        requester: FocusRequester,
    ) {
        index ?: return
        scope.launch {
            listState.scrollToItem(index)
            withFrameNanos { }
            requester.requestFocus()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().tvAcceleratedDpad(),
            contentPadding = PaddingValues(top = dimens.overscanVertical, bottom = dimens.overscanVertical),
        ) {
            catalogBlocks(body, actions, horizontalPadding = dimens.overscanHorizontal, tables = tables, pageHeader = cover)
        }
        TvCatalogEntityHeader(
            header = cover,
            blocks = blocks,
            actions = actions,
            initialFocus = playingRow == null,
            onMoveToTracks = { focusItem(firstTrackIndex, tables.firstTrack) },
            playFocus = paneFocus,
            modifier =
                Modifier
                    .offset { IntOffset(0, if (firstShelfIndex == null) 0 else listState.paneOffset(lastTrackIndex, paneHeight)) }
                    .onSizeChanged { paneHeight = it.height }
                    // The pane rides up with the tracks; stepping into it brings all of it back.
                    .onFocusChanged { if (it.hasFocus) scope.launch { listState.animateScrollToItem(0) } }
                    .padding(start = dimens.overscanHorizontal, top = dimens.overscanVertical),
            status = status,
            actionsModifier =
                Modifier.focusGroup().onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_DOWN -> firstShelfIndex?.let { focusItem(it, tables.firstShelf) } != null
                        else -> false
                    }
                },
        )
    }
}

/**
 * How far the pane is pushed up: not at all while the tracks fill the screen beside it, then with the
 * last track as it scrolls away, and fully off screen once it has. Read at placement, so scrolling
 * never recomposes the page.
 */
private fun LazyListState.paneOffset(
    lastTrackIndex: Int?,
    paneHeight: Int,
): Int {
    lastTrackIndex ?: return 0
    val visible = layoutInfo.visibleItemsInfo
    val last = visible.firstOrNull { it.index == lastTrackIndex }
    return when {
        last != null -> minOf(0, last.offset + last.size - paneHeight)
        (visible.firstOrNull()?.index ?: 0) > lastTrackIndex -> -paneHeight
        else -> 0
    }
}
