package io.github.aedev.flow.ui.tv.screens.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.listenerMessage
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
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
 * the library has songs. It loads only while the section is shown, and again when a scan changes the
 * index or another genre is picked.
 */
@HiltViewModel
class TvLocalLibraryViewModel
    @Inject
    internal constructor(
        private val local: LocalCatalogProvider,
        registry: PluginRegistry,
    ) : ViewModel() {
        /** Null until known, so a restored selection of this section is not dropped before the library answers. */
        val available: StateFlow<Boolean?> =
            combine(registry.state.map { it.selection.metadata != null }, local.hasTracks) { pluginHome, hasTracks ->
                pluginHome && hasTracks
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), null)

        private val selectedFilter = MutableStateFlow<String?>(null)

        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<TvLocalLibraryState> =
            combine(local.account, selectedFilter) { _, filterId -> filterId }
                .mapLatest(::load)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), TvLocalLibraryState(isLoading = true))

        fun selectFilter(option: FilterOption) = selectedFilter.update { current -> option.id.takeUnless { it == current } }

        fun track(item: MetadataItem): MusicTrack? = local.track(item)

        private suspend fun load(filterId: String?): TvLocalLibraryState =
            local.home(HomeRequest(filterId = filterId)).fold(
                onSuccess = { page ->
                    TvLocalLibraryState(filters = page.filters?.options.orEmpty(), selectedFilterId = filterId, blocks = page.blocks)
                },
                onFailure = { error ->
                    Log.w(TAG, "local home failed", error)
                    TvLocalLibraryState(selectedFilterId = filterId, error = error.listenerMessage)
                },
            )

        private companion object {
            const val TAG = "TvLocalLibrary"
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
