package io.github.aedev.flow.ui.tv.screens.search

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionHeader
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import org.junit.Test

class TvSearchStateTest {
    private fun item(id: String) = MetadataItem(id = id, entity = EntityRef(EntityKind.VIDEO, id), title = id)

    private fun collection(
        id: String,
        vararg items: String,
        title: String? = null,
        layout: CollectionLayout = CollectionLayout.HORIZONTAL_SHELF,
    ) = CollectionBlock(
        id = id,
        header = title?.let { CollectionHeader(it) },
        layout = layout,
        defaultItemView = ItemView.LANDSCAPE_CARD,
        items = items.map(::item),
    )

    @Test
    fun `suggestions put matching recent searches first and list each only once, whatever its case`() {
        val merged =
            mergeSuggestions(
                recent = listOf("Cafe del Mar"),
                live = listOf("cafe del mar", "cafe", " ", "cafe music", "Cafe"),
                limit = 3,
            )

        assertThat(merged).containsExactly("Cafe del Mar", "cafe", "cafe music").inOrder()
    }

    @Test
    fun `a continuation extends the results block and appends new shelves after it`() {
        val first =
            TvSearchResults()
                .searching("q", null)
                .withFirstPage(
                    MetadataPage(
                        id = "p",
                        blocks = listOf(collection("results", "a", "b")),
                        filters = FilterControl(listOf(FilterOption("videos", "Videos"))),
                        nextCursor = "c1",
                    ),
                )
        val next =
            first.withNextPage(
                MetadataPage(
                    id = "p",
                    blocks = listOf(collection("results", "b", "c"), collection("shelf:x", "x", title = "Latest")),
                    nextCursor = "c2",
                ),
            )

        assertThat(next.blocks.map { it.id }).containsExactly("results", "shelf:x").inOrder()
        assertThat((next.blocks.first() as CollectionBlock).items.map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(next.nextCursor).isEqualTo("c2")
        assertThat(next.filters.map { it.id }).containsExactly("videos")
    }

    @Test
    fun `a new query or filter drops the old blocks, a repeat keeps them while it loads`() {
        val loaded = TvSearchResults().searching("q", null).withFirstPage(MetadataPage("p", listOf(collection("results", "a"))))

        assertThat(loaded.searching("q", "songs").blocks).isEmpty()
        assertThat(loaded.searching("other", null).blocks).isEmpty()
        assertThat(loaded.searching("q", null).blocks).isEqualTo(loaded.blocks)
    }

    @Test
    fun `results answer only the query and filter they were loaded or are loading for`() {
        val loading = TvSearchResults().searching("q", "songs")
        val loaded = loading.withFirstPage(MetadataPage("p", emptyList()))

        assertThat(loading.answers("q", "songs", inFlight = true)).isTrue()
        assertThat(loading.answers("q", "songs", inFlight = false)).isFalse()
        assertThat(loaded.answers("q", "songs", inFlight = false)).isTrue()
        assertThat(loaded.answers("q", null, inFlight = false)).isFalse()
        assertThat(loaded.failed("no", noPlugin = false).answers("q", "songs", inFlight = false)).isFalse()
    }

    @Test
    fun `picking the selected filter again clears it`() {
        val filtered = TvSearchResults(filterId = "songs")

        assertThat(filtered.toggled("songs")).isNull()
        assertThat(filtered.toggled("albums")).isEqualTo("albums")
    }

    @Test
    fun `only an untitled shelf leading the page is laid out as a grid`() {
        assertThat(listOf(collection("results", "a"), collection("s", "b", title = "More")).resultsGridId()).isEqualTo("results")
        assertThat(listOf(collection("top", "a", title = "Top result")).resultsGridId()).isNull()
        assertThat(listOf(collection("results", "a", layout = CollectionLayout.TRACK_TABLE)).resultsGridId()).isNull()
    }
}
