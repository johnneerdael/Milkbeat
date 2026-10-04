package io.github.aedev.flow.data.catalog

import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.data.library.catalog.LocalRef
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.ProviderAccount
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The catalog the app's home and pages show: the selected metadata plugin's, or, while none is
 * selected, the local library built from the music folders. Local pages and tracks always go to the
 * local library, whichever home opened them.
 */
@Singleton
class CatalogRouter
    @Inject
    constructor(
        private val plugins: PluginMetadataProvider,
        private val local: LocalCatalogProvider,
        private val registry: PluginRegistry,
    ) : MetadataProvider,
        CatalogPlayback {
        private val usesLocal: Boolean get() = registry.state.value.selection.metadata == null

        override val id: String get() = if (usesLocal) local.id else plugins.id

        @OptIn(ExperimentalCoroutinesApi::class)
        override val account: Flow<ProviderAccount> =
            registry.state
                .map { it.selection.metadata == null }
                .distinctUntilChanged()
                .flatMapLatest { localHome -> if (localHome) local.account else plugins.account }

        override suspend fun home(request: HomeRequest): Result<MetadataPage> =
            if (usesLocal) local.home(request) else plugins.home(request)

        override suspend fun page(
            entity: EntityRef,
            cursor: String?,
        ): Result<MetadataPage> = if (LocalRef.parse(entity) != null) local.page(entity, cursor) else plugins.page(entity, cursor)

        override fun track(item: MetadataItem): MusicTrack? = local.track(item) ?: plugins.track(item)
    }
