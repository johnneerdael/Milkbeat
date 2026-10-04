package io.github.aedev.flow.ui.tv.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.FilterOption

/** A catalog home's filters, such as its moods or genres, as one row of chips. */
@Composable
internal fun TvCatalogFilterChips(
    filters: List<FilterOption>,
    selectedId: String?,
    onSelect: (FilterOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dimens = LocalTvDimens.current
    LazyRow(
        modifier = modifier.fillMaxWidth().tvRowFocus(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = dimens.overscanHorizontal, vertical = 8.dp),
    ) {
        items(filters, key = { it.id }) { filter ->
            TvFilterChip(
                label = filter.label,
                selected = filter.id == selectedId,
                onClick = { onSelect(filter) },
            )
        }
    }
}
