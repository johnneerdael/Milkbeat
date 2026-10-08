package io.github.aedev.flow.ui.tv.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.LikedVideoInfo
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.plugin.catalog.LibraryProvider
import io.github.aedev.flow.plugin.catalog.MergedLibraryPagingSource
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.catalog.listenerMessage
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.ui.tv.toTvMusicTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginOperations
import javax.inject.Inject

@HiltViewModel
internal class TvMergedLibraryViewModel
    @Inject
    constructor(
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
        private val metadata: PluginMetadataProvider,
        playlists: PlaylistRepository,
        likes: LikedVideosRepository,
        private val mirrorStore: io.github.aedev.flow.plugin.mirror.PlaylistMirrorStore,
    ) : ViewModel() {
        val localPlaylists = playlists.getAllPlaylistsFlow()
        val localMusicPlaylists = playlists.getMusicPlaylistsFlow()
        val localLikedSongs = likes.getAllLikedVideos().map { songs -> songs.filter { it.isMusic }.map(LikedVideoInfo::toTvMusicTrack) }
        private val ready = MutableStateFlow(false)
        private val generation = MutableStateFlow(0)
        val errors = MutableStateFlow<Map<String, String?>>(emptyMap())
        private var openJob: Job? = null
        private var lastOpened = 0L
        private var lastInstalled = ""
        val installedIdentity =
            registry.state
                .map { state ->
                    state.plugins
                        .filter {
                            it.enabled && MetadataSurface.LIBRARY in
                                it.manifest.roles.metadata
                                    ?.surfaces
                                    .orEmpty()
                        }.joinToString { "${it.id}:${it.manifest.versionCode}" }
                }.distinctUntilChanged()
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

        private val sources =
            combine(registry.state, accounts.accounts, ready) { registry, accounts, ready ->
                if (!ready) {
                    emptyList()
                } else {
                    registry.plugins.mapNotNull { plugin ->
                        val role = plugin.manifest.roles.metadata ?: return@mapNotNull null
                        val account = accounts[plugin.id] as? ProviderAccount.SignedIn ?: return@mapNotNull null
                        if (!plugin.enabled || MetadataSurface.LIBRARY !in role.surfaces) return@mapNotNull null
                        LibraryProvider(plugin.id, plugin.manifest.name, plugin.manifest.versionCode, account.key, role.idSpace)
                    }
                }
            }.distinctUntilChanged()

        private val hiddenCopies =
            combine(mirrorStore.records, registry.state, accounts.accounts) { records, registry, accounts ->
                records
                    .filter { record ->
                        record.ready && registry.plugin(record.key.sourcePlugin) != null &&
                            (accounts[record.key.sourcePlugin] as? ProviderAccount.SignedIn)?.key == record.key.sourceAccount &&
                            (accounts[record.key.targetPlugin] as? ProviderAccount.SignedIn)?.key == record.key.targetAccount
                    }.mapNotNull { record -> record.destination?.let { "${record.key.targetPlugin}:${it.kind}:${it.providerId}" } }
                    .toSet()
            }.distinctUntilChanged()

        val providerPlaylists = pages("playlists")
        val providerLikedSongs = pages("liked")

        @OptIn(ExperimentalCoroutinesApi::class)
        private fun pages(section: String) =
            combine(sources, generation, hiddenCopies) { sources, generation, hidden -> Triple(sources, generation, hidden) }
                .flatMapLatest { (sources, epoch, hidden) ->
                    Pager(PagingConfig(pageSize = 30, enablePlaceholders = false)) {
                        MergedLibraryPagingSource(sources, section, { plugin, request ->
                            metadata.callFor(plugin, PluginOperations.library, request)
                        }, { provider, error ->
                            if (generation.value == epoch) errors.update { it + (provider.name to error.listenerMessage) }
                        }, { provider ->
                            if (generation.value == epoch) errors.update { it - provider.name }
                        }, hiddenCopies = hidden)
                    }.flow
                }.cachedIn(viewModelScope)

        fun open(force: Boolean = false) {
            if (openJob?.isActive == true) return
            val identity =
                registry.state.value.plugins
                    .filter {
                        it.enabled &&
                            MetadataSurface.LIBRARY in
                            it.manifest.roles.metadata
                                ?.surfaces
                                .orEmpty()
                    }.joinToString { "${it.id}:${it.manifest.versionCode}" }
            if (!force && identity == lastInstalled && System.currentTimeMillis() - lastOpened < FRESH_FOR_MS) return
            openJob =
                viewModelScope.launch {
                    ready.value = false
                    errors.value = emptyMap()
                    try {
                        registry.state.value.plugins
                            .filter {
                                it.enabled &&
                                    MetadataSurface.LIBRARY in
                                    it.manifest.roles.metadata
                                        ?.surfaces
                                        .orEmpty()
                            }.forEach { plugin ->
                                try {
                                    accounts.refresh(plugin.id)
                                } catch (error: Exception) {
                                    if (error is CancellationException) throw error
                                    errors.update { it + (plugin.manifest.name to error.listenerMessage) }
                                }
                            }
                        lastInstalled = identity
                        lastOpened = System.currentTimeMillis()
                        if (force) generation.update { it + 1 }
                    } finally {
                        ready.value = true
                    }
                }
        }

        private companion object {
            const val FRESH_FOR_MS = 10 * 60_000L
        }
    }
