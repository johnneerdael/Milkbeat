package io.github.aedev.flow.plugin.catalog

import androidx.paging.PagingSource
import androidx.paging.PagingState
import io.github.aedev.flow.data.paging.pagedItems
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.plugin.PluginErrorCode

internal data class LibraryProvider(
    val id: String,
    val name: String,
    val version: Int,
    val account: String,
    val idSpace: String,
)

internal data class ProviderLibraryItem(
    val provider: LibraryProvider,
    val item: MetadataItem,
) {
    val key: String get() = "${provider.id}:${item.entity.kind}:${item.entity.providerId}"
    val playableTrack by lazy { item.track?.toMusicTrack(provider.id) }
}

internal class MergedLibraryPagingSource(
    private val providers: List<LibraryProvider>,
    private val section: String,
    private val fetch: suspend (String, LibraryRequest) -> Result<MetadataPage>,
    private val onFailure: (LibraryProvider, Throwable) -> Unit = { _, _ -> },
    private val onSuccess: (LibraryProvider) -> Unit = {},
) : PagingSource<Int, ProviderLibraryItem>() {
    private data class Pending(
        val provider: LibraryProvider,
        val section: String,
        val cursor: String?,
    )

    private val pending = ArrayDeque<Pending>()

    private data class Failed(
        val request: Pending,
        val error: Throwable,
    )

    private val failed = ArrayDeque<Failed>()
    private var errorDelivered = false
    private val cursors = mutableMapOf<String, MutableSet<String>>()
    private val seen = mutableSetOf<String>()
    private var initialized = false

    override fun getRefreshKey(state: PagingState<Int, ProviderLibraryItem>): Int? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, ProviderLibraryItem> {
        currentCoroutineContext().ensureActive()
        val first = !initialized
        if (first) {
            pending.addAll(providers.map { Pending(it, section, null) })
            initialized = true
        }
        if (pending.isEmpty() && failed.isNotEmpty()) {
            if (!errorDelivered) {
                errorDelivered = true
                return LoadResult.Error(failed.first().error)
            }
            pending.addLast(failed.removeFirst().request)
            errorDelivered = false
        }
        val batch = if (first) pending.size else minOf(1, pending.size)
        var successful = 0
        val items = mutableListOf<ProviderLibraryItem>()
        repeat(batch) {
            val request = pending.removeFirst()
            val result = request(request)
            currentCoroutineContext().ensureActive()
            result.fold(
                onSuccess = { (page, actualSection) ->
                    successful++
                    onSuccess(request.provider)
                    page.pagedItems().filter { accepts(request.provider, it) }.forEach {
                        val scoped = ProviderLibraryItem(request.provider, it)
                        if (seen.add(scoped.key)) items += scoped
                    }
                    page.nextCursor?.takeIf { cursors.getOrPut(request.provider.id) { mutableSetOf() }.add(it) }?.let {
                        pending.addLast(Pending(request.provider, actualSection, it))
                    }
                },
                onFailure = {
                    if (it is CancellationException) throw it
                    failed.addLast(Failed(request, it))
                    onFailure(request.provider, it)
                },
            )
        }
        if (successful == 0 && failed.isNotEmpty() && pending.isEmpty()) {
            errorDelivered = true
            return LoadResult.Error(failed.first().error)
        }
        return LoadResult.Page(items, null, if (pending.isEmpty() && failed.isEmpty()) null else (params.key ?: 0) + 1)
    }

    private suspend fun request(pending: Pending): Result<Pair<MetadataPage, String>> {
        var actualSection = pending.section
        var result = fetch(pending.provider.id, LibraryRequest(actualSection, pending.cursor))
        val code = (result.exceptionOrNull() as? PluginCallException)?.error?.code
        if (actualSection == "liked" && pending.cursor == null && code in setOf(PluginErrorCode.NOT_FOUND, PluginErrorCode.UNSUPPORTED)) {
            actualSection = "tracks"
            result = fetch(pending.provider.id, LibraryRequest(actualSection, null))
        }
        return result.map { it to actualSection }
    }

    private fun accepts(
        provider: LibraryProvider,
        item: MetadataItem,
    ): Boolean =
        if (section == "liked") {
            item.entity.kind == EntityKind.TRACK || item.entity.kind == EntityKind.MUSIC_VIDEO
        } else {
            item.entity.kind in setOf(EntityKind.PLAYLIST, EntityKind.MIX) &&
                when (provider.idSpace) {
                    "ytm", "yt" -> item.entity.providerId !in setOf("LM", "LL")
                    "spotify" -> item.entity.providerId != "spotify:collection:tracks"
                    else -> true
                }
        }
}
