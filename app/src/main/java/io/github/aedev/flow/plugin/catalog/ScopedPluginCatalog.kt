package io.github.aedev.flow.plugin.catalog

import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.flow.Flow
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.PageRequest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginOperation
import nl.neerdael.milkbeat.plugin.PluginOperations

class ScopedPluginCatalog internal constructor(
    private val owner: PluginMetadataProvider,
    override val id: String,
) : MetadataProvider,
    CatalogPlayback {
    override val account: Flow<ProviderAccount> = owner.accountFor(id)

    override suspend fun home(request: HomeRequest): Result<MetadataPage> = owner.callFor(id, PluginOperations.home, request)

    override suspend fun page(
        entity: EntityRef,
        cursor: String?,
    ): Result<MetadataPage> = owner.callFor(id, PluginOperations.entity, PageRequest(entity, cursor = cursor))

    override fun track(item: MetadataItem): MusicTrack? = item.track?.toMusicTrack(id)

    suspend fun <Request, Response> call(
        operation: PluginOperation<Request, Response>,
        request: Request,
    ): Result<Response> = owner.callFor(id, operation, request)
}
