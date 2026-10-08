package io.github.aedev.flow.ui.tv.screens.search

import io.github.aedev.flow.ui.screens.music.extendedBy
import io.github.aedev.flow.ui.screens.music.withPage
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.PageBlock

/**
 * One half's answer to a search: the page for [query] under [filterId], grown by each continuation
 * page, and the filters its plugin offers. [filters] outlive the query, so the chips stay put while
 * the next search runs.
 */
data class TvSearchResults(
    val query: String = "",
    val filterId: String? = null,
    val filters: List<FilterOption> = emptyList(),
    val blocks: List<PageBlock> = emptyList(),
    val nextCursor: String? = null,
    val loaded: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val noPlugin: Boolean = false,
)

/** [results] holds each chip's answer by [TvSearchSource.key]. */
data class TvSearchUiState(
    val query: String = "",
    val results: Map<String, TvSearchResults> = emptyMap(),
    val musicSuggestions: List<String> = emptyList(),
    val videoSuggestions: List<String> = emptyList(),
) {
    fun results(source: TvSearchSource): TvSearchResults = results[source.key] ?: TvSearchResults()

    fun withResults(
        source: TvSearchSource,
        update: (TvSearchResults) -> TvSearchResults,
    ): TvSearchUiState = copy(results = results + (source.key to update(results(source))))

    /** The typeahead of the chip on screen only: another provider's suggestions would search something else. */
    fun suggestions(source: TvSearchSource): List<String> =
        when (source) {
            is TvSearchSource.Music -> musicSuggestions
            TvSearchSource.Videos -> videoSuggestions
        }
}

/** Whether these results are, or are becoming, the answer to [query] under [filterId]. */
internal fun TvSearchResults.answers(
    query: String,
    filterId: String?,
    inFlight: Boolean,
): Boolean = this.query == query && this.filterId == filterId && error == null && (loaded || inFlight)

/** A new search starts: a different query or filter drops the old blocks, a repeat keeps them until page one lands. */
internal fun TvSearchResults.searching(
    query: String,
    filterId: String?,
): TvSearchResults {
    val same = this.query == query && this.filterId == filterId
    return copy(
        query = query,
        filterId = filterId,
        blocks = if (same) blocks else emptyList(),
        nextCursor = if (same) nextCursor else null,
        loaded = same && loaded,
        isLoading = true,
        isLoadingMore = false,
        error = null,
        noPlugin = false,
    )
}

internal fun TvSearchResults.withFirstPage(page: MetadataPage): TvSearchResults =
    copy(
        filters = page.filters?.options ?: filters,
        blocks = emptyList<PageBlock>().withPage(page.blocks),
        nextCursor = page.nextCursor,
        loaded = true,
        isLoading = false,
    )

/** A continuation extends the collections it shares ids with, as the plugin contract says. */
internal fun TvSearchResults.withNextPage(page: MetadataPage): TvSearchResults =
    copy(
        filters = page.filters?.options ?: filters,
        blocks = blocks.extendedBy(page.blocks),
        nextCursor = page.nextCursor,
        isLoadingMore = false,
    )

internal fun TvSearchResults.failed(
    message: String?,
    noPlugin: Boolean,
): TvSearchResults = copy(isLoading = false, isLoadingMore = false, error = message.orEmpty(), noPlugin = noPlugin)

internal fun TvSearchResults.cleared(): TvSearchResults = TvSearchResults(filterId = filterId, filters = filters)

/** Picking the selected filter again goes back to the unfiltered results. */
internal fun TvSearchResults.toggled(filterId: String): String? = filterId.takeUnless { it == this.filterId }

/**
 * The chips under the query: past searches that match first, as YouTube shows them, then the
 * plugins' typeahead, each only once whatever its case.
 */
internal fun mergeSuggestions(
    recent: List<String>,
    live: List<String>,
    limit: Int,
): List<String> {
    val seen = HashSet<String>()
    return (recent + live)
        .map(String::trim)
        .filter { it.isNotEmpty() && seen.add(it.lowercase()) }
        .take(limit)
}

/**
 * The collection a results page is built around, laid out as a grid: a first collection without a
 * title that the plugin serves as a shelf, as a video search's results are. Track tables stay tables.
 */
internal fun List<PageBlock>.resultsGridId(): String? =
    (firstOrNull() as? CollectionBlock)
        ?.takeIf { it.header == null && it.layout == CollectionLayout.HORIZONTAL_SHELF }
        ?.id

/** The first item on the page that points at [entity]. */
internal fun List<PageBlock>.itemFor(entity: EntityRef): MetadataItem? =
    asSequence()
        .filterIsInstance<CollectionBlock>()
        .flatMap { it.items }
        .firstOrNull { it.entity == entity }
