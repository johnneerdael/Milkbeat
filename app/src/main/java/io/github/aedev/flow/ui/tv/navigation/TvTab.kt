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
 * The top-level destination that owns the page on screen: the last tab route on the back stack [routes],
 * so a detail page keeps the tab it was opened from, also after the shell is rebuilt.
 */
internal fun ownerDestination(routes: List<String?>): TvDestination =
    routes.asReversed().firstNotNullOfOrNull(::tabDestination) ?: TvDestination.start

private fun tabDestination(route: String?): TvDestination? =
    if (route == TvRoutes.MUSIC_FOLDERS_SETTINGS) {
        TvDestination.SETTINGS
    } else {
        TvDestination.entries.firstOrNull { it.route == route }
    }

/** The tab the rail shows as current: the [owner]'s, with the music tabs shown as [music]. */
internal fun shownRailTab(
    owner: TvDestination,
    railTabs: List<TvTab>,
    music: TvTab.Music,
): TvTab = TvTab.Fixed(owner).takeIf { owner != TvDestination.MUSIC && it in railTabs } ?: music

/** How a rail press or Back reaches a tab. */
internal enum class TvTabNavigation {
    /** Pop to the tab's root page, which is on the back stack already. */
    POP_TO_ROOT,

    /** Replace the shown tab with another, restoring the pages that tab had open. */
    SWITCH,
}

/**
 * Pressing the tab that owns the page on screen returns to its root, rather than restoring that same
 * page again. The music tabs are the start destination and stay at the bottom of the back stack, so
 * they are always popped to: their saved pages may belong to another provider or another tab.
 */
internal fun tabNavigation(
    target: TvDestination,
    owner: TvDestination,
): TvTabNavigation = if (target == owner || target == TvDestination.start) TvTabNavigation.POP_TO_ROOT else TvTabNavigation.SWITCH

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
