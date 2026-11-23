package io.github.aedev.flow.ui.tv.catalog

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionHeader
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import org.junit.Test

class TvCatalogEndFocusTest {
    private val actions = TvCatalogActions({ null }, {}, { _, _, _, _ -> }, {}, onShowAllFilter = {})
    private val item = MetadataItem("one", EntityRef(EntityKind.ALBUM, "one"), "One")

    @Test
    fun `empty trailing blocks do not hide the last actionable row`() {
        val results = CollectionBlock("results", null, CollectionLayout.HORIZONTAL_SHELF, ItemView.COVER_CARD, listOf(item))
        val empty = CollectionBlock("empty", null, CollectionLayout.HORIZONTAL_SHELF, ItemView.COVER_CARD, emptyList())
        assertThat(lastActionableBlockIndex(listOf(results, empty), "results", actions)).isEqualTo(0)
    }

    @Test
    fun `zero row table with show all is actionable`() {
        val results = CollectionBlock("results", null, CollectionLayout.HORIZONTAL_SHELF, ItemView.COVER_CARD, listOf(item))
        val table =
            CollectionBlock(
                "table",
                CollectionHeader("Tracks"),
                CollectionLayout.TRACK_TABLE,
                ItemView.TRACK_ROW,
                emptyList(),
                showAllFilterId = "tracks",
            )
        assertThat(lastActionableBlockIndex(listOf(results, table), "results", actions)).isEqualTo(1)
    }

    @Test
    fun `an artist header with a follow action remains reachable before an empty block`() {
        val header = EntityHeader("artist", HeaderStyle.PORTRAIT, EntityRef(EntityKind.ARTIST, "artist"), "Artist")
        val empty = CollectionBlock("empty", null, CollectionLayout.TRACK_TABLE, ItemView.TRACK_ROW, emptyList())
        val followActions = TvCatalogActions({ null }, {}, { _, _, _, _ -> }, {}, follow = TvCatalogFollow({ false }, {}))
        assertThat(lastActionableBlockIndex(listOf(header, empty), null, followActions)).isEqualTo(0)
    }
}
