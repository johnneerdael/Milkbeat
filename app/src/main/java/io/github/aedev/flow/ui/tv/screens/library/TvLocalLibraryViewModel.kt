package io.github.aedev.flow.ui.tv.screens.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.listenerMessage
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.PageBlock
import javax.inject.Inject

data class TvLocalLibraryState(
    val filters: List<FilterOption> = emptyList(),
    val selectedFilterId: String? = null,
    val blocks: List<PageBlock> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * The local library's home inside Library, offered while a metadata plugin owns the app's home and
 * the library has songs. It reloads when a scan changes the index.
 */
@HiltViewModel
class TvLocalLibraryViewModel
    @Inject
    internal constructor(
        private val local: LocalCatalogProvider,
        registry: PluginRegistry,
    ) : ViewModel() {
        val available: StateFlow<Boolean> =
            combine(registry.state.map { it.selection.metadata != null }, local.hasTracks) { pluginHome, hasTracks ->
                pluginHome &&
                    hasTracks
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), false)

        private val _state = MutableStateFlow(TvLocalLibraryState())
        val state: StateFlow<TvLocalLibraryState> = _state.asStateFlow()
        private var job: Job? = null
        private var loaded = false

        init {
            viewModelScope.launch {
                local.account.drop(1).collect { if (loaded) load(_state.value.selectedFilterId) }
            }
        }

        fun open() {
            if (!loaded) load(null)
        }

        fun selectFilter(option: FilterOption) = load(option.id.takeUnless { it == _state.value.selectedFilterId })

        fun track(item: MetadataItem): MusicTrack? = local.track(item)

        private fun load(filterId: String?) {
            loaded = true
            job?.cancel()
            job =
                viewModelScope.launch {
                    _state.update { it.copy(selectedFilterId = filterId, isLoading = it.blocks.isEmpty(), error = null) }
                    local
                        .home(HomeRequest(filterId = filterId))
                        .onSuccess { page ->
                            _state.update { it.copy(filters = page.filters?.options.orEmpty(), blocks = page.blocks, isLoading = false) }
                        }.onFailure { error ->
                            Log.w(TAG, "local home failed", error)
                            _state.update { it.copy(blocks = emptyList(), isLoading = false, error = error.listenerMessage) }
                        }
                }
        }

        private companion object {
            const val TAG = "TvLocalLibrary"
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
