package io.github.aedev.flow.ui.tv.screens.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.catalog.MusicSources
import io.github.aedev.flow.data.catalog.MusicTab
import io.github.aedev.flow.data.catalog.MusicTabs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The music tabs and which one shows; [ready] is false while the start tab cannot be told yet. */
data class TvMusicTabsState(
    val tabs: List<MusicTab> = emptyList(),
    val selected: MusicSource? = null,
    val ready: Boolean = false,
)

/** The last music tab used, as stored; [Unknown] until the store has been read. */
internal sealed interface Remembered {
    data object Unknown : Remembered

    data class Known(
        val source: MusicSource?,
    ) : Remembered
}

/**
 * The tab shown: the one picked while it still exists, else the last one used once it exists, else,
 * when every account has answered, the first. Opening another tab while the remembered one is still
 * being checked would show the wrong provider for a moment.
 */
internal fun resolveMusicTabs(
    tabs: MusicTabs,
    remembered: Remembered,
    chosen: MusicSource?,
): TvMusicTabsState {
    val available = tabs.tabs.map { it.source }
    val last = (remembered as? Remembered.Known)?.source
    val selected =
        when {
            chosen != null && chosen in available -> chosen
            remembered == Remembered.Unknown -> null
            last != null && last in available -> last
            !tabs.settled -> null
            else -> available.firstOrNull()
        }
    val ready = selected != null || (remembered != Remembered.Unknown && tabs.settled)
    return TvMusicTabsState(tabs.tabs, selected, ready)
}

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
