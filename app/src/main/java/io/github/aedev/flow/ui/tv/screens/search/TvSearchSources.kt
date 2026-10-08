package io.github.aedev.flow.ui.tv.screens.search

import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.SearchRequest
import nl.neerdael.milkbeat.catalog.SuggestRequest
import nl.neerdael.milkbeat.catalog.Suggestions
import nl.neerdael.milkbeat.plugin.PluginOperations

/** A music tab's provider as search answers it: result pages and typeahead. */
internal interface TvSearchBackend {
    suspend fun search(request: SearchRequest): Result<MetadataPage>

    suspend fun suggest(query: String): Result<Suggestions>
}

internal fun PluginMetadataProvider.searchBackend(pluginId: String): TvSearchBackend =
    object : TvSearchBackend {
        override suspend fun search(request: SearchRequest) = callFor(pluginId, PluginOperations.search, request)

        override suspend fun suggest(query: String) = callFor(pluginId, PluginOperations.suggest, SuggestRequest(query))
    }

/** The local library searches its index and offers no typeahead. */
internal fun LocalCatalogProvider.searchBackend(): TvSearchBackend =
    object : TvSearchBackend {
        override suspend fun search(request: SearchRequest) = this@searchBackend.search(request)

        override suspend fun suggest(query: String) = Result.success(Suggestions(emptyList()))
    }

/** Whether [error] says no metadata plugin is chosen, rather than that the plugin failed. */
internal val Throwable.isNoPlugin: Boolean
    get() = this is NoMetadataPluginException

/**
 * The chip shown: the one the listener picked while it is offered, else [start], which follows the
 * tabs as they settle; null while no music tab can search.
 */
internal fun shownSearchSource(
    picked: MusicSource?,
    chips: List<MusicSource>,
    start: MusicSource?,
): MusicSource? = picked?.takeIf { it in chips } ?: start
