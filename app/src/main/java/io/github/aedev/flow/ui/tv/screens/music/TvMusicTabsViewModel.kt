package io.github.aedev.flow.ui.tv.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.catalog.MusicSources
import io.github.aedev.flow.ui.tv.navigation.Remembered
import io.github.aedev.flow.ui.tv.navigation.TvMusicTabsState
import io.github.aedev.flow.ui.tv.navigation.resolveMusicTabs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Activity-scoped: the rail and the music route share which music tab shows. */
@HiltViewModel
class TvMusicTabsViewModel
    @Inject
    constructor(
        private val sources: MusicSources,
    ) : ViewModel() {
        private val chosen = MutableStateFlow<MusicSource?>(null)

        val state: StateFlow<TvMusicTabsState> =
            combine(
                sources.tabs,
                sources.lastUsed.map<MusicSource?, Remembered> { Remembered.Known(it) }.onStart { emit(Remembered.Unknown) },
                chosen,
                ::resolveMusicTabs,
            ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), TvMusicTabsState())

        fun select(source: MusicSource) {
            chosen.value = source
            viewModelScope.launch { sources.remember(source) }
        }

        private companion object {
            const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
        }
    }
