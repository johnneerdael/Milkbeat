package io.github.aedev.flow.ui.screens.music

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.data.library.catalog.LocalLibraryEmptyException
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.catalog.listenerMessage
import io.github.aedev.flow.plugin.runtime.transientRetryDelayMs
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.PageBlock
import nl.neerdael.milkbeat.catalog.ProviderAccount

data class MusicHomeFeedState(
    val filters: List<FilterOption> = emptyList(),
    val selectedFilterId: String? = null,
    val blocks: List<PageBlock> = emptyList(),
    /** Starts true: before the first load the page is loading, not empty with a retry to focus. */
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    /** No music plugin is chosen: the page offers to add one instead of an error. */
    val needsPlugin: Boolean = false,
    /** The local library is the home but has no songs yet: its first scan is running, or found none. */
    val libraryEmpty: Boolean = false,
)

/**
 * One music tab's home page, block for block in the order its provider serves it. Every continuation
 * page is followed, as YouTube Music's own client does while scrolling to the end. Without a
 * provider, the tab asks for a plugin or a music folder and fetches nothing.
 */
@HiltViewModel(assistedFactory = MusicHomeFeedViewModel.Factory::class)
class MusicHomeFeedViewModel internal constructor(
    private val provider: MetadataProvider?,
    private val playback: CatalogPlayback,
    installation: Flow<Any?> = flowOf(null),
) : ViewModel() {
    @AssistedInject
    constructor(
        @Assisted source: MusicSource?,
        plugins: PluginMetadataProvider,
        local: dagger.Lazy<LocalCatalogProvider>,
    ) : this(catalogOf(source, plugins, local))

    private constructor(catalog: FeedCatalog?) : this(
        catalog?.provider,
        catalog?.playback ?: CatalogPlayback { null },
        catalog?.installation ?: flowOf(null),
    )

    @AssistedFactory
    interface Factory {
        fun create(source: MusicSource?): MusicHomeFeedViewModel
    }

    private val _state = MutableStateFlow(MusicHomeFeedState(isLoading = provider != null, needsPlugin = provider == null))
    val state: StateFlow<MusicHomeFeedState> = _state.asStateFlow()

    private val account = provider?.account ?: flowOf(ProviderAccount.Anonymous)

    /** Whose home this is: the account, and the installation answering for it (anonymous across a reinstall). */
    private val identity = combine(account, installation) { account, installed -> account to installed }.distinctUntilChanged()

    val isAccountExpired: StateFlow<Boolean> =
        account
            .map { it is ProviderAccount.Expired }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)

    private var job: Job? = null
    private var loadedKey: FeedKey? = null
    private var loadedAtMs = 0L
    private var wasShown = false

    init {
        // A sign-in, sign-out, expiry or reinstall swaps whose home this is; never keep showing the old one. It is
        // followed only while the tab is shown: a hidden tab neither fetches nor listens, and catches up on its
        // next visit, when the identity it then sees differs from the one its home was loaded for.
        viewModelScope.launch {
            _state.subscriptionCount
                .map { it > 0 }
                .distinctUntilChanged()
                .collectLatest { shown ->
                    if (shown) wasShown = true
                    if (!shown) {
                        // A load left running for a tab no longer shown is stopped after the usual grace; the
                        // next visit loads again.
                        if (!wasShown || job?.isActive != true) return@collectLatest
                        delay(SUBSCRIPTION_TIMEOUT_MS)
                        job?.cancel()
                        loadedKey = null
                        _state.update { it.copy(isLoading = false, isLoadingMore = false) }
                        return@collectLatest
                    }
                    identity.collect { identity ->
                        val loaded = loadedKey ?: return@collect
                        if (loaded.identity != identity) load(filterId = null, force = true)
                    }
                }
        }
    }

    fun load(force: Boolean = false) = load(_state.value.selectedFilterId, force)

    fun track(item: MetadataItem): MusicTrack? = playback.track(item)

    /**
     * A station or playlist of this home seeds its radio as this tab's provider's, not another tab's.
     * The local library has no playlist radio; its queues seed from their first song instead.
     */
    fun radioSeed(playlistId: String?): String? {
        val id = playlistId ?: return null
        val source = provider?.takeUnless { it.id == LocalCatalogProvider.ID } ?: return null
        return ProviderEntityReference.encode(source.id, EntityRef(EntityKind.PLAYLIST, id))
    }

    fun selectFilter(option: FilterOption) = load(option.id.takeUnless { it == _state.value.selectedFilterId }, force = true)

    private fun load(
        filterId: String?,
        force: Boolean,
    ) {
        val provider = provider ?: return
        if (!force && job?.isActive == true) return
        if (!force && loadedKey != null && System.currentTimeMillis() - loadedAtMs < FRESH_FOR_MS) return
        job?.cancel()
        job =
            viewModelScope.launch {
                val key = FeedKey(provider.id, identity.first(), filterId)
                // A refresh of the same feed keeps what it shows (its blocks, or the failure with its retry, and
                // the focus on them) until page one replaces it.
                val sameFeed = key == loadedKey
                loadedKey = key
                loadedAtMs = System.currentTimeMillis()
                _state.update {
                    if (sameFeed) {
                        it.copy(selectedFilterId = filterId, isLoading = true, isLoadingMore = false)
                    } else {
                        it.copy(
                            selectedFilterId = filterId,
                            blocks = emptyList(),
                            isLoading = true,
                            isLoadingMore = false,
                            error = null,
                            needsPlugin = false,
                            libraryEmpty = false,
                        )
                    }
                }
                val first = firstPage(filterId) ?: return@launch
                _state.update {
                    it.copy(
                        filters = first.filters?.options ?: it.filters,
                        blocks = emptyList<PageBlock>().withPage(first.blocks),
                        isLoading = false,
                        isLoadingMore = first.nextCursor != null,
                        error = null,
                        needsPlugin = false,
                        libraryEmpty = false,
                    )
                }
                var cursor = first.nextCursor
                val followed = mutableSetOf<String>()
                while (cursor != null && followed.add(cursor)) {
                    val next =
                        page(HomeRequest(filterId = filterId, cursor = cursor)).getOrElse { error ->
                            Log.w(TAG, "home continuation failed", error)
                            loadedAtMs = 0L
                            null
                        } ?: break
                    _state.update { it.copy(blocks = it.blocks.withPage(next.blocks)) }
                    cursor = next.nextCursor
                }
                _state.update { it.copy(isLoadingMore = false) }
                Log.d(TAG, "home: ${_state.value.blocks.size} blocks over ${followed.size + 1} pages")
            }
    }

    /**
     * Page one of the home, or null once its failure is shown. While the page has nothing else to show,
     * a transient failure (a provider's one-off server error, a dropped connection) is fetched again on
     * the backoff schedule, with the failure and its retry on screen meanwhile.
     */
    private suspend fun firstPage(filterId: String?): MetadataPage? {
        var attempt = 0
        while (true) {
            val error = page(HomeRequest(filterId = filterId)).fold(onSuccess = { return it }, onFailure = { it })
            Log.w(TAG, "home failed", error)
            loadedAtMs = 0L
            _state.update {
                it.copy(
                    isLoading = false,
                    error = error.listenerMessage,
                    needsPlugin = error is NoMetadataPluginException,
                    libraryEmpty = error is LocalLibraryEmptyException,
                )
            }
            if (_state.value.blocks.isNotEmpty()) return null
            delay(transientRetryDelayMs(error, attempt++) ?: return null)
            // The screen collects only while it is shown, so a listener who left the tab costs no fetch
            // until they come back to it.
            _state.subscriptionCount.first { it > 0 }
            _state.update { it.copy(isLoading = true) }
        }
    }

    private suspend fun page(request: HomeRequest): Result<MetadataPage> {
        val result = checkNotNull(provider).home(request)
        // Providers runCatching, so a superseded load comes back as a failure; it must not write state.
        currentCoroutineContext().ensureActive()
        return result
    }

    private data class FeedKey(
        val providerId: String,
        val identity: Pair<ProviderAccount, Any?>,
        val filterId: String?,
    )

    private companion object {
        const val TAG = "MusicHomeFeed"
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        const val FRESH_FOR_MS = 10 * 60_000L
    }
}

private class FeedCatalog(
    val provider: MetadataProvider,
    val playback: CatalogPlayback,
    val installation: Flow<Any?>,
)

private fun catalogOf(
    source: MusicSource?,
    plugins: PluginMetadataProvider,
    local: dagger.Lazy<LocalCatalogProvider>,
): FeedCatalog? =
    when (source) {
        is MusicSource.Plugin -> plugins.scoped(source.id).let { FeedCatalog(it, it, plugins.installationOf(source.id)) }
        MusicSource.Local -> local.get().let { FeedCatalog(it, it, flowOf(null)) }
        null -> null
    }
