package io.github.aedev.flow.ui.tv.screens.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.data.local.SearchHistoryItem
import io.github.aedev.flow.data.local.SearchHistoryRepository
import io.github.aedev.flow.data.local.SearchType
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.plugin.catalog.PluginMetadataProvider
import io.github.aedev.flow.plugin.catalog.listenerMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.SearchRequest
import javax.inject.Inject

/**
 * TV search through every music tab's provider that can search. Typing searches the chip on screen
 * and asks it for typeahead once the typing pauses; another chip searches and suggests when it is
 * shown, so every fetch follows one cause. A newer query cancels the older one's fetches, and each
 * chip keeps its own filter and results. Nothing searches until a chip is shown.
 */
@HiltViewModel
class TvSearchViewModel internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val music: (MusicSource) -> TvSearchBackend,
    private val trackFor: (MusicSource, MetadataItem) -> MusicTrack?,
    private val history: SearchHistoryRepository,
    private val stats: VideoStatsRecorder,
) : ViewModel() {
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        metadata: PluginMetadataProvider,
        local: dagger.Lazy<LocalCatalogProvider>,
        history: SearchHistoryRepository,
        stats: VideoStatsRecorder,
    ) : this(
        savedStateHandle,
        { source ->
            when (source) {
                is MusicSource.Plugin -> metadata.searchBackend(source.id)
                MusicSource.Local -> local.get().searchBackend()
            }
        },
        { source, item ->
            when (source) {
                is MusicSource.Plugin -> metadata.scoped(source.id).track(item)
                MusicSource.Local -> local.get().track(item)
            }
        },
        history,
        stats,
    )

    private val _state = MutableStateFlow(TvSearchUiState(query = savedStateHandle[QUERY_KEY] ?: ""))
    val state: StateFlow<TvSearchUiState> = _state.asStateFlow()

    /** The saved searches, most recent first; empty while search history is switched off. */
    val recentSearches: StateFlow<List<SearchHistoryItem>> =
        history.getSearchHistoryFlow().stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    private var source: MusicSource? = null
    private val searchJobs = HashMap<String, Job>()
    private val identities = HashMap<String, String?>()
    private val moreJobs = HashMap<String, Job>()
    private var suggestJob: Job? = null
    private var suggestAfterPause = false
    private var lastRecordedQuery: String? = null

    /** A music result of the chip on screen as a playable track. */
    fun track(item: MetadataItem): MusicTrack? = source?.let { trackFor(it, item) }

    /** Typed on the keyboard: searches once typing pauses. */
    fun onQueryChange(query: String) = setQuery(query, searchDelayMs = DEBOUNCE_MS)

    /** Picked from a suggestion or the voice prompt: searches straight away and is saved. */
    fun submit(
        query: String,
        type: SearchType,
    ) {
        pick(query)
        rememberSearch(query, type)
    }

    /** Picked from the recent searches: searches straight away. */
    fun pick(query: String) = setQuery(query, searchDelayMs = 0L)

    /** The chip on screen; it searches the current query now unless it already answers it. */
    fun showSource(target: MusicSource) {
        val previous = source
        source = target
        if (previous != target) {
            if (previous != null) {
                // Only the chip on screen searches; the one left stops, and searches again when shown again.
                val stopped = listOfNotNull(searchJobs.remove(previous.key), moreJobs.remove(previous.key)).filter { it.isActive }
                stopped.forEach(Job::cancel)
                if (stopped.isNotEmpty()) {
                    _state.update { state ->
                        state.withResults(previous) { it.copy(isLoading = false, isLoadingMore = false) }
                    }
                }
            }
            restartSuggestions()
        }
        search(target, _state.value.results(target).filterId, delayMs = 0L)
    }

    /**
     * Keeps the chips in [sources], each with its provider's identity. A chip gone (sign-out, disabled,
     * removed) or whose account or installation changed forgets its answer and typeahead, so the same
     * query asks the provider again instead of showing another account's results.
     */
    fun retainSources(sources: Map<String, String?>) {
        val changed = identities.filter { (key, identity) -> key in sources && sources[key] != identity }.keys
        identities.clear()
        identities.putAll(sources)
        val shown = source
        if (shown != null && (shown.key !in sources || shown.key in changed)) {
            // A typeahead already asking the old account or installation must not answer for the new one.
            if (shown.key !in sources) source = null
            restartSuggestions()
        }
        // A search still in its typing pause has a job but no results yet.
        val gone = (_state.value.results.keys + searchJobs.keys + moreJobs.keys).filter { it !in sources || it in changed }
        if (gone.isEmpty()) return
        gone.forEach { key ->
            searchJobs.remove(key)?.cancel()
            moreJobs.remove(key)?.cancel()
        }
        _state.update { it.copy(results = it.results - gone.toSet()) }
        // The chip on screen asks its provider again at once rather than waiting on an answer that was dropped.
        if (shown != null && shown.key in gone && shown.key in sources) search(shown, filterId = null, delayMs = 0L)
    }

    /** Selects a filter of the chip on screen, or drops it when picked again; "Show all" selects its section's. */
    fun selectFilter(filterId: String) {
        val target = source ?: return
        val next = _state.value.results(target).toggled(filterId)
        if (_state.value.query.isBlank()) {
            _state.update { state -> state.withResults(target) { it.copy(filterId = next) } }
        } else {
            search(target, next, delayMs = 0L)
        }
    }

    fun showAll(filterId: String) {
        val target = source ?: return
        if (_state.value.results(target).filterId != filterId) selectFilter(filterId)
    }

    /** The end of [target]'s results came into view: fetches the next page once. */
    fun loadMore(target: MusicSource) {
        val results = _state.value.results(target)
        val cursor = results.nextCursor ?: return
        if (!results.loaded || results.isLoading || results.isLoadingMore) return
        _state.update { state -> state.withResults(target) { it.copy(isLoadingMore = true) } }
        val request = SearchRequest(results.query, results.filterId, cursor)
        moreJobs[target.key] =
            viewModelScope.launch {
                val page = attempt { music(target).search(request) }
                currentCoroutineContext().ensureActive()
                _state.update { state ->
                    state.withResults(target) { current ->
                        if (current.nextCursor != cursor) return@withResults current
                        page.fold(
                            onSuccess = current::withNextPage,
                            onFailure = { error ->
                                Log.w(TAG, "$target search continuation failed", error)
                                current.copy(isLoadingMore = false)
                            },
                        )
                    }
                }
            }
    }

    /** Saves [query] once the listener acts on it, not for every pause in typing. */
    fun rememberSearch(
        query: String,
        type: SearchType = SearchType.TEXT,
    ) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty()) viewModelScope.launch { history.saveSearchQuery(trimmed, type) }
    }

    fun forgetSearch(item: SearchHistoryItem) {
        viewModelScope.launch { history.deleteSearchItem(item.id) }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            history.clearSearchHistory()
            stats.onSearchHistoryCleared()
        }
    }

    private fun setQuery(
        query: String,
        searchDelayMs: Long,
    ) {
        savedStateHandle[QUERY_KEY] = query
        _state.update { it.copy(query = query) }
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            clear()
            return
        }
        suggest(trimmed, afterPause = true)
        source?.let { search(it, _state.value.results(it).filterId, searchDelayMs) }
    }

    private fun clear() {
        suggestJob?.cancel()
        (searchJobs.values + moreJobs.values).forEach(Job::cancel)
        searchJobs.clear()
        moreJobs.clear()
        _state.update {
            it.copy(
                results = it.results.mapValues { (_, results) -> results.cleared() },
                suggestions = emptyList(),
            )
        }
    }

    private fun search(
        target: MusicSource,
        filterId: String?,
        delayMs: Long,
    ) {
        val query = _state.value.query.trim()
        if (query.isEmpty()) return
        if (_state.value.results(target).answers(query, filterId, searchJobs[target.key]?.isActive == true)) return
        searchJobs[target.key]?.cancel()
        moreJobs[target.key]?.cancel()
        val start = { _state.update { state -> state.withResults(target) { it.searching(query, filterId) } } }
        if (delayMs == 0L) start()
        searchJobs[target.key] =
            viewModelScope.launch {
                if (delayMs > 0L) {
                    delay(delayMs)
                    start()
                }
                record(query)
                val page = attempt { music(target).search(SearchRequest(query, filterId)) }
                currentCoroutineContext().ensureActive()
                _state.update { state ->
                    state.withResults(target) { current ->
                        page.fold(
                            onSuccess = current::withFirstPage,
                            onFailure = { error ->
                                if (!error.isNoPlugin) Log.w(TAG, "$target search failed", error)
                                current.failed(error.listenerMessage, noPlugin = error.isNoPlugin)
                            },
                        )
                    }
                }
            }
    }

    /**
     * Drops the typeahead shown and asks the chip on screen afresh. One still waiting out the typing
     * pause keeps waiting for it, so a chip switch or account change never asks twice for one pause.
     */
    private fun restartSuggestions() {
        val afterPause = suggestJob?.isActive == true && suggestAfterPause
        suggestJob?.cancel()
        _state.update { it.copy(suggestions = emptyList()) }
        _state.value.query
            .trim()
            .takeIf { it.isNotEmpty() }
            ?.let { suggest(it, afterPause) }
    }

    private fun suggest(
        query: String,
        afterPause: Boolean,
    ) {
        suggestJob?.cancel()
        val target = source ?: return
        suggestAfterPause = afterPause
        suggestJob =
            viewModelScope.launch {
                if (afterPause) delay(DEBOUNCE_MS)
                val answer = attempt { music(target).suggest(query) }
                currentCoroutineContext().ensureActive()
                if (target == source) _state.update { it.copy(suggestions = answer.getOrNull()?.queries.orEmpty()) }
            }
    }

    private suspend fun record(query: String) {
        val normalized = query.lowercase()
        if (normalized == lastRecordedQuery) return
        lastRecordedQuery = normalized
        stats.onSearch(query.takeIf { history.isSearchHistoryEnabled() })
    }

    /** A plugin call that fails in any way other than being cancelled comes back as a failure. */
    private suspend fun <T> attempt(call: suspend () -> Result<T>): Result<T> =
        try {
            call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }

    private companion object {
        const val TAG = "TvSearch"
        const val QUERY_KEY = "query"
        const val DEBOUNCE_MS = 350L
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
