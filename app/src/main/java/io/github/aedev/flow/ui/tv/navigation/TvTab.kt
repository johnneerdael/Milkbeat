package io.github.aedev.flow.ui.tv.navigation

import io.github.aedev.flow.data.catalog.MusicSource

/** A tab of the rail: one of the music tabs, which share the [TvDestination.MUSIC] route, or a fixed destination. */
sealed interface TvTab {
    val destination: TvDestination

    /** [source] is null for the single Music tab shown while no provider or folder offers music. */
    data class Music(
        val source: MusicSource?,
    ) : TvTab {
        override val destination: TvDestination get() = TvDestination.MUSIC
    }

    data class Fixed(
        override val destination: TvDestination,
    ) : TvTab
}

/**
 * The tab the rail shows as current: a top-level route's own tab, Settings for the folders settings
 * detail, else the tab a detail page was opened from while the rail still has it, else [music].
 */
internal fun shownRailTab(
    routeTab: TvTab?,
    settingsDetail: Boolean,
    detailOwner: TvTab,
    railTabs: List<TvTab>,
    music: TvTab.Music,
): TvTab =
    routeTab
        ?: if (settingsDetail) TvTab.Fixed(TvDestination.SETTINGS) else detailOwner.takeIf { it in railTabs } ?: music

/** The rail item focus enters on: the shown tab, or the first item while no item is the shown tab. */
internal fun railFocusTab(
    railTabs: List<TvTab>,
    shown: TvTab?,
): TvTab? = shown?.takeIf { it in railTabs } ?: railTabs.firstOrNull()

/** The tabs Back can return to: those the rail still has, so a provider gone since is skipped. */
internal fun prunedTabHistory(
    history: List<TvTab>,
    railTabs: List<TvTab>,
): List<TvTab> = history.filter { it in railTabs }
