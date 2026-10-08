package io.github.aedev.flow.ui.screens.music

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.data.library.catalog.LocalRef
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.catalog.listenerMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.PageBlock
import nl.neerdael.milkbeat.catalog.ProviderAccount
import javax.inject.Inject

data class CatalogPageState(
    val blocks: List<PageBlock> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    internal val sourceKey: String? = null,
)

/** One artist, album or playlist page of the music provider, with a long playlist's tracks followed to the end. */
@HiltViewModel
class CatalogPageViewModel
    internal constructor(
        savedStateHandle: SavedStateHandle,
        defaultProvider: MetadataProvider = NoCatalog,
        defaultPlayback: CatalogPlayback = NoCatalog,
        private val subscriptions: SubscriptionRepository,
        pluginCatalog: PluginMetadataProvider? = null,
        private val mirrors: io.github.aedev.flow.plugin.mirror.PlaylistMirrorCoordinator? = null,
        localCatalog: LocalCatalogProvider? = null,
    ) : ViewModel() {
        /** Every route names the provider whose page it is; one that does not shows the page's error. */
        @Inject
        constructor(
            savedStateHandle: SavedStateHandle,
            subscriptions: SubscriptionRepository,
            pluginCatalog: PluginMetadataProvider,
            mirrors: io.github.aedev.flow.plugin.mirror.PlaylistMirrorCoordinator,
            localCatalog: LocalCatalogProvider,
        ) : this(savedStateHandle, NoCatalog, NoCatalog, subscriptions, pluginCatalog, mirrors, localCatalog)

        val sourcePluginId: String? = savedStateHandle.get<String>(PROVIDER_ARG)
        private val local = localCatalog?.takeIf { sourcePluginId == LocalCatalogProvider.ID }
        private val scoped = sourcePluginId?.takeIf { local == null }?.let { checkNotNull(pluginCatalog).scoped(it) }
        private val provider: MetadataProvider = local ?: scoped ?: defaultProvider
        private val playback: CatalogPlayback = local ?: scoped ?: defaultPlayback

        fun radioSeed(id: String?): String? {
            val value = id ?: return null
            val ref = if (value == entity.providerId) entity else EntityRef(EntityKind.PLAYLIST, value)
            return ProviderEntityReference.encode(provider.id, ref)
        }

        private val entity =
            EntityRef(
                kind = EntityKind.valueOf(checkNotNull(savedStateHandle.get<String>(KIND_ARG))),
                providerId = checkNotNull(savedStateHandle.get<String>(ID_ARG)),
            )

        private val _state = MutableStateFlow(CatalogPageState())
        val sourceIdentity = provider.account.map { sourceKey(it) }.distinctUntilChanged()
        val state: StateFlow<CatalogPageState> =
            combine(_state, sourceIdentity) { state, identity ->
                if (state.sourceKey != null && state.sourceKey != identity) CatalogPageState() else state
            }.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS, replayExpirationMillis = 0),
                CatalogPageState(),
            )

        private fun sourceKey(account: ProviderAccount): String =
            when (account) {
                is ProviderAccount.SignedIn -> "${provider.id}:signed-in:${account.key}"
                ProviderAccount.Anonymous -> "${provider.id}:anonymous"
                ProviderAccount.Expired -> "${provider.id}:expired"
            }

        private var job: Job? = null
        private val _mirror =
            MutableStateFlow(
                io.github.aedev.flow.plugin.mirror
                    .PlaylistMirrorState(),
            )
        val mirror: StateFlow<io.github.aedev.flow.plugin.mirror.PlaylistMirrorState> = _mirror
        private val _mirrorTarget = MutableStateFlow<nl.neerdael.milkbeat.plugin.PluginManifest?>(null)
        val mirrorTarget: StateFlow<nl.neerdael.milkbeat.plugin.PluginManifest?> = _mirrorTarget
        private var mirrorJob: Job? = null

        fun retryMirror() {
            val header =
                _state.value.blocks
                    .filterIsInstance<EntityHeader>()
                    .firstOrNull() ?: return
            prepareMirror(header.title, header.artwork)
        }

        private fun prepareMirror(
            title: String,
            artwork: nl.neerdael.milkbeat.catalog.Artwork?,
        ) {
            mirrorJob?.cancel()
            _mirror.value =
                io.github.aedev.flow.plugin.mirror
                    .PlaylistMirrorState()
            val coordinator = mirrors?.takeIf { LocalRef.parse(entity) == null } ?: return
            val identity = _state.value.sourceKey
            mirrorJob =
                viewModelScope.launch {
                    combine(coordinator.observeSelectedKey(provider.id, entity), sourceIdentity) { key, currentIdentity ->
                        key.takeIf { currentIdentity == identity }
                    }.distinctUntilChanged().collectLatest { key ->
                        _mirror.value =
                            io.github.aedev.flow.plugin.mirror
                                .PlaylistMirrorState()
                        _mirrorTarget.value = key?.let(coordinator::target)
                        if (key == null) return@collectLatest
                        coordinator.open(provider.id, entity, title, artwork)
                        coordinator.state(key).collect { progress ->
                            currentCoroutineContext().ensureActive()
                            if (_state.value.sourceKey == identity) _mirror.value = progress
                        }
                    }
                }
        }

        /** A provider's artist can be followed; an artist of the local library is only its tags. */
        val canFollow: Boolean get() = entity.kind == EntityKind.ARTIST && LocalRef.parse(entity) == null

        /** Whether this artist is followed in the app's library; nothing else can be followed. */
        val following: StateFlow<Boolean> =
            if (canFollow) {
                subscriptions
                    .isSubscribed(entity.providerId)
                    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)
            } else {
                MutableStateFlow(false)
            }

        fun track(item: MetadataItem): MusicTrack? {
            val position =
                _state.value.blocks
                    .filterIsInstance<CollectionBlock>()
                    .filter { it.layout == CollectionLayout.TRACK_TABLE }
                    .flatMap { it.items }
                    .indexOf(item)
                    .takeIf { it >= 0 }
            return playback.track(item)?.copy(sourcePosition = position)
        }

        fun toggleFollow(header: EntityHeader) {
            if (!canFollow) return
            viewModelScope.launch {
                if (following.value) {
                    subscriptions.unsubscribe(header.entity.providerId)
                } else {
                    subscriptions.subscribe(
                        ChannelSubscription(
                            channelId = header.entity.providerId,
                            channelName = header.title,
                            channelThumbnail = header.artwork?.url.orEmpty(),
                            isMusic = true,
                        ),
                    )
                }
            }
        }

        fun load(expectedIdentity: String? = null) {
            if (expectedIdentity != null && job?.isActive == true && _state.value.sourceKey == expectedIdentity) return
            job?.cancel()
            job =
                viewModelScope.launch {
                    val identity = sourceKey(provider.account.first())
                    if (_state.value.sourceKey == identity && _state.value.blocks.isNotEmpty()) return@launch
                    _state.value = CatalogPageState(sourceKey = identity)
                    val first =
                        provider.page(entity).getOrElse { error ->
                            currentCoroutineContext().ensureActive()
                            if (sourceKey(provider.account.first()) != identity) return@launch
                            Log.w(TAG, "page ${entity.kind} ${entity.providerId} failed", error)
                            _state.update { it.copy(isLoading = false, error = error.listenerMessage) }
                            return@launch
                        }
                    currentCoroutineContext().ensureActive()
                    if (sourceKey(provider.account.first()) != identity) return@launch
                    _state.update { it.copy(blocks = emptyList<PageBlock>().withPage(first.blocks), isLoading = false) }
                    first.blocks
                        .filterIsInstance<EntityHeader>()
                        .firstOrNull()
                        ?.let { prepareMirror(it.title, it.artwork) }
                    var cursor = first.nextCursor
                    val seen = mutableSetOf<String>()
                    var pages = 0
                    while (cursor != null && seen.add(cursor) && pages++ < MAX_CONTINUATION_PAGES) {
                        val next = provider.page(entity, cursor).getOrNull() ?: break
                        currentCoroutineContext().ensureActive()
                        if (sourceKey(provider.account.first()) != identity) return@launch
                        _state.update { it.copy(blocks = it.blocks.extendedBy(next.blocks)) }
                        cursor = next.nextCursor
                    }
                }
        }

        companion object {
            const val PROVIDER_ARG = "catalogProvider"
            const val KIND_ARG = "kind"
            const val ID_ARG = "id"
            private const val TAG = "CatalogPage"
            private const val SUBSCRIPTION_TIMEOUT_MS = 5_000L

            // A playlist of a few thousand tracks, in pages of a hundred.
            private const val MAX_CONTINUATION_PAGES = 30
        }
    }

private object NoCatalog : MetadataProvider, CatalogPlayback {
    override val id: String = "none"
    override val account: Flow<ProviderAccount> = flowOf(ProviderAccount.Anonymous)

    override suspend fun home(request: HomeRequest): Result<MetadataPage> = Result.failure(NoMetadataPluginException())

    override suspend fun page(
        entity: EntityRef,
        cursor: String?,
    ): Result<MetadataPage> = Result.failure(NoMetadataPluginException())

    override fun track(item: MetadataItem): MusicTrack? = null
}
