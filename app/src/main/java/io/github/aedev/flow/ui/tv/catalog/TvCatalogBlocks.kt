package io.github.aedev.flow.ui.tv.catalog

import android.view.KeyEvent
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.PageBlock

/**
 * What a catalog page's items and buttons do. The screen owns playback and navigation; [trackFor]
 * says which items the player can take. [follow] is offered on artist headers when set, and
 * [onShowAllFilter] re-runs the page with a collection's `showAllFilterId`, as a search does.
 */
internal class TvCatalogActions(
    val trackFor: (MetadataItem) -> MusicTrack?,
    val onPlayMix: (MusicTrack) -> Unit,
    val onPlayList: (track: MusicTrack, queue: List<MusicTrack>, source: String, radioPlaylistId: String?) -> Unit,
    val onOpen: (EntityRef) -> Unit,
    val follow: TvCatalogFollow? = null,
    val onShowAllFilter: ((String) -> Unit)? = null,
) {
    /** A song picked on its own plays with its mix; anything else opens. */
    fun pick(item: MetadataItem) {
        val track = trackFor(item)
        if (track != null) onPlayMix(track) else onOpen(item.entity)
    }
}

internal class TvCatalogFollow(
    val isFollowing: () -> Boolean,
    val toggle: (EntityHeader) -> Unit,
)

/** Where a collection page's track tables start beside its cover, and what its pane's buttons move to. */
internal class TvCatalogTableLayout(
    val startInset: Dp,
    val firstTrack: FocusRequester,
    val firstShelf: FocusRequester,
    val playingTrackId: String? = null,
    val playingTrackFocus: FocusRequester? = null,
    val paneFocus: FocusRequester? = null,
    val playingTrackPosition: Int? = null,
)

/**
 * Every block of a catalog page, in order, as lazy list items. A song picked from a shelf plays with
 * its own mix; a track picked from a table plays the table from there, continuing with the mix of
 * the collection the page is about. The collection named [gridBlockId] is laid out as a grid.
 */
internal fun LazyListScope.catalogBlocks(
    blocks: List<PageBlock>,
    actions: TvCatalogActions,
    horizontalPadding: Dp,
    firstBlockModifier: Modifier = Modifier,
    tables: TvCatalogTableLayout? = null,
    gridBlockId: String? = null,
    pageHeader: EntityHeader? = blocks.firstNotNullOfOrNull { it as? EntityHeader },
) {
    val firstTable = blocks.firstOrNull { it.isTrackTable }
    val firstShelf = blocks.firstOrNull { it is CollectionBlock && !it.isTrackTable }
    var trackOffset = 0
    blocks.forEachIndexed { index, block ->
        val blockModifier = if (index == 0) firstBlockModifier else Modifier
        when (block) {
            is EntityHeader -> {
                item(key = block.id) { TvCatalogEntityHeader(block, blocks, actions, blockModifier) }
            }

            is CollectionBlock -> {
                if (block.id == gridBlockId) {
                    catalogGrid(block, actions, horizontalPadding)
                } else if (block.layout == CollectionLayout.TRACK_TABLE) {
                    val queueId = (pageHeader?.tracks ?: block.showAll)?.providerId
                    val source = block.header?.title ?: pageHeader?.title.orEmpty()
                    catalogTrackTable(
                        collection = block,
                        onItemClick = { item -> playFromTable(item, block, source, queueId, actions) },
                        onOpen = actions.onOpen,
                        startPadding = horizontalPadding + (tables?.startInset ?: 0.dp),
                        endPadding = horizontalPadding,
                        firstRowFocus = tables?.firstTrack.takeIf { block === firstTable },
                        onShowAllFilter = actions.onShowAllFilter,
                        playingTrackId = tables?.playingTrackId,
                        playingTrackFocus = tables?.playingTrackFocus,
                        paneFocus = tables?.paneFocus,
                        playingTrackPosition = tables?.playingTrackPosition?.minus(trackOffset),
                    )
                    trackOffset += block.items.size
                } else {
                    val shelfModifier =
                        if (block === firstShelf &&
                            tables != null
                        ) {
                            blockModifier.focusRequester(tables.firstShelf).focusGroup()
                        } else {
                            blockModifier
                        }
                    item(key = block.id) { TvCatalogShelfBlock(block, actions, shelfModifier) }
                }
            }
        }
    }
}

/** The list index at which [catalogBlocks] places [block]'s first item; for a table, its first row. */
internal fun List<PageBlock>.catalogIndexOf(block: PageBlock): Int {
    val before = take(indexOf(block)).sumOf { it.catalogItemCount }
    return if (block is CollectionBlock && block.isTrackTable && block.header != null) before + 1 else before
}

internal fun List<PageBlock>.catalogPlayingRow(
    sourcePosition: Int?,
    trackId: String?,
): Int? {
    if (sourcePosition == null && trackId == null) return null
    var trackOffset = 0
    for (block in this) {
        if (block !is CollectionBlock || !block.isTrackTable) continue
        val row = sourcePosition?.minus(trackOffset) ?: block.items.indexOfFirst { it.track?.ref?.providerId == trackId }
        if (row in block.items.indices) return catalogIndexOf(block) + row
        trackOffset += block.items.size
    }
    return null
}

private val PageBlock.catalogItemCount: Int
    get() =
        if (this is CollectionBlock && isTrackTable) {
            items.size + (if (header != null) 1 else 0)
        } else {
            1
        }

internal val PageBlock.isTrackTable: Boolean
    get() = this is CollectionBlock && layout == CollectionLayout.TRACK_TABLE

internal fun playFromTable(
    item: MetadataItem,
    table: CollectionBlock,
    source: String,
    queueId: String?,
    actions: TvCatalogActions,
) {
    val track = actions.trackFor(item)
    if (track == null) {
        actions.onOpen(item.entity)
        return
    }
    if (LocalMediaIds.isLocal(track.videoId)) {
        actions.onPlayMix(track)
    } else {
        actions.onPlayList(track, table.items.mapNotNull(actions.trackFor), source, queueId)
    }
}

/** A header in its style, with the buttons that play what the page lists and, for an artist, Follow. */
@Composable
internal fun TvCatalogEntityHeader(
    header: EntityHeader,
    blocks: List<PageBlock>,
    actions: TvCatalogActions,
    modifier: Modifier = Modifier,
    actionsModifier: Modifier = Modifier,
    initialFocus: Boolean = true,
    onMoveToTracks: (() -> Unit)? = null,
    playFocus: FocusRequester? = null,
    status: @Composable () -> Unit = {},
) {
    val table = remember(blocks) { blocks.firstOrNull { it.isTrackTable } as CollectionBlock? }
    val tracks = remember(table) { table?.items.orEmpty().mapNotNull(actions.trackFor) }
    val queueId = (header.tracks ?: table?.showAll)?.providerId
    val follow = actions.follow?.takeIf { header.entity.kind == EntityKind.ARTIST }
    val buttons: @Composable RowScope.() -> Unit = {
        // The first button present takes the page's initial focus.
        val first = tracks.firstOrNull()
        if (first != null) {
            TvButton(
                text = stringResource(R.string.play),
                onClick = { actions.onPlayList(first, tracks, header.title, queueId) },
                icon = Icons.Outlined.PlayArrow,
                modifier =
                    (if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier)
                        .then(if (initialFocus) Modifier.tvInitialFocus(header.id) else Modifier),
            )
            TvButton(
                text = stringResource(R.string.shuffle),
                onClick = {
                    val shuffled = tracks.shuffled()
                    actions.onPlayList(shuffled.first().copy(shuffleRequested = true), shuffled, header.title, queueId)
                },
                icon = Icons.Outlined.Shuffle,
                modifier = if (header.station == null && onMoveToTracks != null) Modifier.moveRightToTracks(onMoveToTracks) else Modifier,
            )
            header.station?.let { station ->
                TvButton(
                    text = stringResource(R.string.tv_catalog_mix),
                    onClick = { actions.onPlayList(first, listOf(first), header.title, station.providerId) },
                    icon = Icons.Outlined.Radio,
                    modifier = if (onMoveToTracks != null) Modifier.moveRightToTracks(onMoveToTracks) else Modifier,
                )
            }
        }
        follow?.let {
            val following = it.isFollowing()
            TvButton(
                text = stringResource(if (following) R.string.subscribed else R.string.subscribe),
                onClick = { it.toggle(header) },
                icon = if (following) Icons.Outlined.Check else Icons.Outlined.PersonAdd,
                modifier = if (first == null) Modifier.tvInitialFocus(header.id) else Modifier,
            )
        }
    }
    when (header.style) {
        HeaderStyle.PORTRAIT -> TvCatalogPortraitHeader(header, modifier, actionsModifier, buttons)
        HeaderStyle.COVER -> TvCatalogCoverPane(header, actions.onOpen, modifier, actionsModifier, status = status, actions = buttons)
    }
}

@Composable
private fun TvCatalogShelfBlock(
    collection: CollectionBlock,
    actions: TvCatalogActions,
    modifier: Modifier,
) {
    val queueTitle = collection.header?.title.orEmpty()
    val tracks = remember(collection.items) { collection.items.associate { it.id to actions.trackFor(it) } }
    val queue = remember(tracks) { tracks.values.filterNotNull() }
    TvCatalogCollection(
        collection = collection,
        onItemClick = { item ->
            val track = tracks[item.id]
            if (track != null) actions.onPlayMix(track) else actions.onOpen(item.entity)
        },
        onPlayAll =
            { actions.onPlayList(queue.first(), queue, queueTitle, null) }
                .takeIf { queue.isNotEmpty() && queue.size == collection.items.size },
        onOpen = actions.onOpen,
        modifier = modifier,
        onShowAllFilter = actions.onShowAllFilter,
    )
}

private fun Modifier.moveRightToTracks(move: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            move()
            true
        } else {
            false
        }
    }
