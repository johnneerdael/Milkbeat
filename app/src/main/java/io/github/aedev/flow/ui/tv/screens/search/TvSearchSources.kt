package io.github.aedev.flow.ui.tv.screens.search

import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import io.github.aedev.flow.plugin.catalog.NoVideoPluginException
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.SearchRequest
import nl.neerdael.milkbeat.catalog.SuggestRequest
import nl.neerdael.milkbeat.catalog.Suggestions
import nl.neerdael.milkbeat.plugin.PluginOperations

/** One chip of TV search: a music tab's provider, or videos through the video plugin. */
sealed interface TvSearchSource {
    val key: String

    data class Music(
        val source: MusicSource,
    ) : TvSearchSource {
        override val key: String get() = source.key
    }

    data object Videos : TvSearchSource {
        override val key: String = "videos"
    }
}

/** A plugin that answers one half of search: result pages and typeahead. */
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

internal fun PluginVideoProvider.searchBackend(): TvSearchBackend =
    object : TvSearchBackend {
        override suspend fun search(request: SearchRequest) = this@searchBackend.search(request)

        override suspend fun suggest(query: String) = this@searchBackend.suggest(query)
    }

/** Whether [error] says no plugin is chosen for that half, rather than that the plugin failed. */
internal val Throwable.isNoPlugin: Boolean
    get() = this is NoMetadataPluginException || this is NoVideoPluginException

/** The chip shown: the one the listener picked while it is offered, else [start], which follows the tabs as they settle. */
internal fun shownSearchSource(
    picked: TvSearchSource?,
    chips: List<TvSearchSource>,
    start: TvSearchSource,
): TvSearchSource = picked?.takeIf { it in chips } ?: start
