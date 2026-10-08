package io.github.aedev.flow.ui.tv.navigation

import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.catalog.MusicTab
import io.github.aedev.flow.data.catalog.MusicTabs

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
 * The tab shown: the one picked while it still exists, else the last one used once it exists and its
 * account has answered, else, when every account has answered, the first. Opening another tab while
 * the remembered one is still being checked would show the wrong provider for a moment, and opening
 * the remembered one before its account answers would load its home twice.
 */
internal fun resolveMusicTabs(
    tabs: MusicTabs,
    remembered: Remembered,
    chosen: MusicSource?,
): TvMusicTabsState {
    val available = tabs.tabs.map { it.source }
    val last = (remembered as? Remembered.Known)?.source
    val lastTab = tabs.tabs.firstOrNull { it.source == last }
    val chosenTab = tabs.tabs.firstOrNull { it.source == chosen }
    val selected =
        when {
            chosenTab != null -> chosenTab.source.takeUnless { chosenTab.accountPending }
            remembered == Remembered.Unknown -> null
            lastTab != null -> lastTab.source.takeUnless { lastTab.accountPending }
            !tabs.settled -> null
            else -> available.firstOrNull()
        }
    val ready = selected != null || (remembered != Remembered.Unknown && tabs.settled)
    return TvMusicTabsState(tabs.tabs, selected, ready)
}
