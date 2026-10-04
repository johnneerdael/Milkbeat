package io.github.aedev.flow.ui.screens.music

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.library.catalog.LocalLibraryEmptyException
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import io.github.aedev.flow.plugin.catalog.listenerMessage
import io.github.aedev.flow.plugin.runtime.transientRetryDelayMs
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.PageBlock
import nl.neerdael.milkbeat.catalog.ProviderAccount
import javax.inject.Inject

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
 * The music provider's home page, block for block in the order it is served. Every continuation
 * page is followed, as YouTube Music's own client does while scrolling to the end.
 */
@HiltViewModel
class MusicHomeFeedViewModel
    @Inject
    constructor(
        private val provider: MetadataProvider,
        private val playback: CatalogPlayback,
    ) : ViewModel() {
        private val _state = MutableStateFlow(MusicHomeFeedState())
        val state: StateFlow<MusicHomeFeedState> = _state.asStateFlow()

        val isAccountExpired: StateFlow<Boolean> =
            provider.account
                .map { it is ProviderAccount.Expired }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)

        private var job: Job? = null
        private var loadedKey: FeedKey? = null
        private var loadedAtMs = 0L

        init {
            // A sign-in, sign-out or expiry swaps whose home this is; never keep showing the old one.
            viewModelScope.launch {
                provider.account.collect { account ->
                    val loaded = loadedKey ?: return@collect
                    if (loaded.account != account || loaded.providerId != provider.id) load(filterId = null, force = true)
                }
            }
        }

        fun load(force: Boolean = false) = load(_state.value.selectedFilterId, force)

        fun track(item: MetadataItem): MusicTrack? = playback.track(item)

        fun selectFilter(option: FilterOption) = load(option.id.takeUnless { it == _state.value.selectedFilterId }, force = true)

        private fun load(
            filterId: String?,
            force: Boolean,
        ) {
            if (!force && job?.isActive == true) return
            if (!force && loadedKey != null && System.currentTimeMillis() - loadedAtMs < FRESH_FOR_MS) return
            job?.cancel()
            job =
                viewModelScope.launch {
                    val key = FeedKey(provider.id, provider.account.first(), filterId)
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
                _state.update { it.copy(isLoading = true) }
            }
        }

        private suspend fun page(request: HomeRequest): Result<MetadataPage> {
            val result = provider.home(request)
            // Providers runCatching, so a superseded load comes back as a failure; it must not write state.
            currentCoroutineContext().ensureActive()
            return result
        }

        private data class FeedKey(
            val providerId: String,
            val account: ProviderAccount,
            val filterId: String?,
        )

        private companion object {
            const val TAG = "MusicHomeFeed"
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
            const val FRESH_FOR_MS = 10 * 60_000L
        }
    }
