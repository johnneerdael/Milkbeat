package io.github.aedev.flow.ui.tv.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvMusicTrackRow
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.focus.tvEndFocus
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.MetadataItem

/**
 * A track table inside a lazy list, one item per row so a long playlist composes only what is on
 * screen: its title and Show all, then each track with its number or artwork, credits, album and
 * duration.
 */
internal fun LazyListScope.catalogTrackTable(
    collection: CollectionBlock,
    onItemClick: (MetadataItem) -> Unit,
    onOpen: (EntityRef) -> Unit,
    startPadding: Dp,
    endPadding: Dp,
    firstRowFocus: FocusRequester? = null,
    onShowAllFilter: ((String) -> Unit)? = null,
    playingTrackId: String? = null,
    playingTrackFocus: FocusRequester? = null,
    paneFocus: FocusRequester? = null,
    playingTrackPosition: Int? = null,
    endFocus: FocusRequester? = null,
    isLastBlock: Boolean = false,
) {
    // A column most rows leave empty is noise; YouTube names the album only on some playlist tracks.
    val showAlbum = collection.items.count { it.album != null } * 2 >= collection.items.size
    val padding = Modifier.padding(start = startPadding, end = endPadding)
    collection.header?.let { header ->
        item(key = "${collection.id}/header") {
            TvCatalogTableHeader(
                title = header.title,
                onShowAll =
                    collection.showAll?.let { target -> { onOpen(target) } }
                        ?: collection.showAllFilterId?.let { filterId -> onShowAllFilter?.let { show -> { show(filterId) } } },
                modifier = padding.tvEndFocus(isLastBlock && collection.items.isEmpty(), endFocus),
            )
        }
    }
    // Row keys carry the table's id: two tables of one page may list the same track.
    itemsIndexed(collection.items, key = { index, _ -> "${collection.id}/row/$index" }) { index, item ->
        val isPlaying =
            if (playingTrackPosition != null) {
                index == playingTrackPosition
            } else {
                playingTrackId != null && item.track?.ref?.providerId == playingTrackId
            }
        val rowModifier =
            (if (index == 0 && firstRowFocus != null) padding.focusRequester(firstRowFocus) else padding)
                .focusProperties { left = paneFocus ?: FocusRequester.Default }
        TvCatalogTrackRow(
            item = item,
            showAlbum = showAlbum,
            onClick = { onItemClick(item) },
            modifier =
                (
                    if (playingTrackFocus != null && isPlaying) {
                        rowModifier.focusRequester(playingTrackFocus)
                    } else {
                        rowModifier
                    }
                ).tvEndFocus(isLastBlock && index == collection.items.lastIndex, endFocus),
        )
    }
}

@Composable
private fun TvCatalogTrackRow(
    item: MetadataItem,
    showAlbum: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    TvMusicTrackRow(
        title = item.title,
        subtitle = item.subtitle,
        thumbnailUrl = item.artwork?.url.orEmpty(),
        durationSeconds = item.durationSeconds ?: 0,
        onClick = onClick,
        modifier = modifier,
        ordinal = item.ordinal,
        album = item.album.takeIf { showAlbum },
    )
}

@Composable
private fun TvCatalogTableHeader(
    title: String,
    onShowAll: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TvSectionHeader(title = title)
        onShowAll?.let {
            TvButton(
                text = stringResource(R.string.tv_catalog_show_all),
                onClick = it,
                icon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            )
        }
    }
}
