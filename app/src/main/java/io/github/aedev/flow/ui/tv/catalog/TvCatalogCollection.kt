package io.github.aedev.flow.ui.tv.catalog

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.tv.components.TvArtistCard
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvIconButton
import io.github.aedev.flow.ui.tv.components.TvMediaRow
import io.github.aedev.flow.ui.tv.components.TvMusicCollectionCard
import io.github.aedev.flow.ui.tv.components.TvMusicLandscapeCard
import io.github.aedev.flow.ui.tv.components.TvMusicTrackRow
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.focus.ProvideTvRowPivot
import io.github.aedev.flow.ui.tv.focus.tvRowEntersAtStart
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionHeader
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem

/**
 * One catalog collection on TV: its header (context line, avatar, Play all) above either a shelf
 * of cards in the shape each item asks for, or compact track rows read down each column.
 */
@Composable
internal fun TvCatalogCollection(
    collection: CollectionBlock,
    onItemClick: (MetadataItem) -> Unit,
    onPlayAll: (() -> Unit)?,
    onOpen: (EntityRef) -> Unit,
    modifier: Modifier = Modifier,
    onShowAllFilter: ((String) -> Unit)? = null,
) {
    val dimens = LocalTvDimens.current
    val header = collection.header
    val openHeader = header?.target?.takeIf { it.kind.isBrowsable }?.let { target -> { onOpen(target) } }
    val showAll = collection.showAllFilterId?.let { filterId -> onShowAllFilter?.let { show -> { show(filterId) } } }
    val hasActions = header != null && (onPlayAll != null || openHeader != null || showAll != null)
    val actionsFocus = remember { FocusRequester() }
    // The header's buttons sit at the far right, outside the beam of most cards below them, so a
    // plain Up skipped them for the shelf above; leaving the shelf upwards lands on them instead.
    val itemsModifier =
        Modifier.focusProperties {
            @OptIn(ExperimentalComposeUiApi::class)
            exit = { direction ->
                if (direction == FocusDirection.Up && hasActions) actionsFocus else FocusRequester.Default
            }
        }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        header?.let {
            TvCatalogHeader(
                header = it,
                onPlayAll = onPlayAll,
                onOpen = openHeader,
                onShowAll = showAll,
                actionsModifier =
                    Modifier
                        .focusRequester(actionsFocus)
                        .focusProperties {
                            // Nothing sits right of the header's buttons; a plain search from there
                            // reached the mini player's Close button below.
                            @OptIn(ExperimentalComposeUiApi::class)
                            exit = { direction -> if (direction == FocusDirection.Right) FocusRequester.Cancel else FocusRequester.Default }
                        }.focusGroup(),
                modifier = Modifier.padding(horizontal = dimens.overscanHorizontal),
            )
        }
        when (collection.layout) {
            // A track table is laid out by the page's lazy list; composed on its own it is a shelf.
            CollectionLayout.HORIZONTAL_SHELF, CollectionLayout.TRACK_TABLE -> TvCatalogShelf(collection, onItemClick, itemsModifier)

            CollectionLayout.MULTI_COLUMN_LIST -> TvCatalogTrackColumns(collection, onItemClick, itemsModifier)
        }
    }
}

@Composable
private fun TvCatalogHeader(
    header: CollectionHeader,
    onPlayAll: (() -> Unit)?,
    onOpen: (() -> Unit)?,
    onShowAll: (() -> Unit)?,
    actionsModifier: Modifier,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTvDimens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        header.avatar?.let { avatar ->
            Surface(
                shape = if (header.target?.kind?.isPerson == true) CircleShape else MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                AsyncImage(
                    model = avatar.url,
                    contentDescription = null,
                    modifier = Modifier.size(dimens.collectionAvatarSize),
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            header.context?.let {
                Text(
                    text = it.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TvSectionHeader(title = header.title)
        }
        Row(
            modifier = actionsModifier,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onOpen?.let {
                TvIconButton(
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.tv_catalog_open, header.title),
                    onClick = it,
                )
            }
            onPlayAll?.let {
                TvButton(
                    text = stringResource(R.string.play_all),
                    onClick = it,
                    icon = Icons.Outlined.PlayArrow,
                )
            }
            onShowAll?.let {
                TvButton(
                    text = stringResource(R.string.tv_catalog_show_all),
                    onClick = it,
                    icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                )
            }
        }
    }
}

@Composable
private fun TvCatalogShelf(
    collection: CollectionBlock,
    onItemClick: (MetadataItem) -> Unit,
    modifier: Modifier,
) {
    TvMediaRow(items = collection.items, key = MetadataItem::id, modifier = modifier) { item ->
        TvCatalogItemCard(item = item, view = item.view ?: collection.defaultItemView, onClick = { onItemClick(item) })
    }
}

/** One item as a card in the shape its [view] asks for; a track row stands as a square cover. */
@Composable
internal fun TvCatalogItemCard(
    item: MetadataItem,
    view: ItemView,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val artwork = item.artwork?.url.orEmpty()
    when (view) {
        ItemView.ARTIST_PORTRAIT -> {
            TvArtistCard(name = item.title, thumbnailUrl = artwork, onClick = onClick, modifier = modifier)
        }

        ItemView.LANDSCAPE_CARD -> {
            TvMusicLandscapeCard(
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = artwork,
                onClick = onClick,
                modifier = modifier,
            )
        }

        ItemView.COVER_CARD, ItemView.TRACK_ROW -> {
            TvMusicCollectionCard(
                title = item.title,
                subtitle = item.subtitle,
                thumbnailUrl = artwork,
                onClick = onClick,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TvCatalogTrackColumns(
    collection: CollectionBlock,
    onItemClick: (MetadataItem) -> Unit,
    modifier: Modifier,
) {
    val dimens = LocalTvDimens.current
    val rows = minOf(TRACK_ROWS, collection.items.size)
    val gridState = rememberLazyGridState()
    val first = remember { FocusRequester() }
    ProvideTvRowPivot {
        LazyHorizontalGrid(
            rows = GridCells.Fixed(rows),
            state = gridState,
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(dimens.trackRowHeight * rows)
                    .tvRowEntersAtStart(
                        first = first,
                        isAtStart = { gridState.firstVisibleItemIndex == 0 },
                        scrollToStart = { gridState.scrollToItem(0) },
                    ).tvRowFocus(),
            contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal),
            horizontalArrangement = Arrangement.spacedBy(dimens.itemSpacing),
        ) {
            itemsIndexed(collection.items, key = { _, item -> item.id }) { index, item ->
                TvMusicTrackRow(
                    title = item.title,
                    subtitle = item.subtitle,
                    thumbnailUrl = item.artwork?.url.orEmpty(),
                    durationSeconds = item.durationSeconds ?: 0,
                    onClick = { onItemClick(item) },
                    modifier =
                        Modifier
                            .width(dimens.trackColumnWidth)
                            .then(if (index == 0) Modifier.focusRequester(first) else Modifier),
                )
            }
        }
    }
}

/** What the TV app has a page for; a listener's profile has none yet. */
internal val EntityKind.isBrowsable: Boolean
    get() = this == EntityKind.ARTIST || this == EntityKind.ALBUM || this == EntityKind.PLAYLIST

private const val TRACK_ROWS = 4
