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
