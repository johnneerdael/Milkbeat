package io.github.aedev.flow.ui.tv.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.tv.components.TV_GRID_COLUMNS
import io.github.aedev.flow.ui.tv.focus.tvEndFocus
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem

private val GridRowHeadroom = 8.dp

/**
 * A collection as rows of [TV_GRID_COLUMNS] cards inside a page's lazy list, one lazy item per row,
 * so a long list of results composes only what is on screen and can grow at its end. Wide and square
 * cards fill their cell; round portraits keep their size, centred in it.
 */
internal fun LazyListScope.catalogGrid(
    collection: CollectionBlock,
    actions: TvCatalogActions,
    horizontalPadding: Dp,
    endFocus: FocusRequester? = null,
    isLastBlock: Boolean = false,
) {
    val rows = collection.items.chunked(TV_GRID_COLUMNS)
    itemsIndexed(rows, key = { index, _ -> "${collection.id}/row$index" }) { index, row ->
        TvCatalogGridRow(
            items = row,
            defaultView = collection.defaultItemView,
            onItemClick = actions::pick,
            modifier =
                Modifier
                    .padding(horizontal = horizontalPadding, vertical = GridRowHeadroom)
                    .tvEndFocus(isLastBlock && index == rows.lastIndex, endFocus),
        )
    }
}

@Composable
private fun TvCatalogGridRow(
    items: List<MetadataItem>,
    defaultView: ItemView,
    onItemClick: (MetadataItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().tvRowFocus(),
        horizontalArrangement = Arrangement.spacedBy(LocalTvDimens.current.itemSpacing),
    ) {
        items.forEach { item ->
            val view = item.view ?: defaultView
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                TvCatalogItemCard(
                    item = item,
                    view = view,
                    onClick = { onItemClick(item) },
                    modifier = if (view == ItemView.ARTIST_PORTRAIT) Modifier else Modifier.fillMaxWidth(),
                )
            }
        }
        repeat(TV_GRID_COLUMNS - items.size) { Spacer(Modifier.weight(1f)) }
    }
}
