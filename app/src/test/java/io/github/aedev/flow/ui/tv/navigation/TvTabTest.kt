package io.github.aedev.flow.ui.tv.navigation

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.MusicSource
import org.junit.Test

class TvTabTest {
    private val youtube = TvTab.Music(MusicSource.Plugin("nl.neerdael.youtube-music"))
    private val local = TvTab.Music(MusicSource.Local)
    private val search = TvTab.Fixed(TvDestination.SEARCH)
    private val rail = listOf(youtube, local, search)

    @Test
    fun `a detail page keeps the tab it was opened from`() {
        assertThat(shownRailTab(routeTab = null, settingsDetail = false, detailOwner = search, railTabs = rail, music = local))
            .isEqualTo(search)
    }

    @Test
    fun `a detail page whose owner is not on the rail shows the music tab`() {
        // The shell is rebuilt after Now Playing closes, forgetting the owner it had.
        assertThat(shownRailTab(null, false, TvTab.Music(null), rail, youtube)).isEqualTo(youtube)
    }

    @Test
    fun `a top-level route is its own tab and the folders settings detail belongs to Settings`() {
        assertThat(shownRailTab(search, false, local, rail, youtube)).isEqualTo(search)
        assertThat(shownRailTab(null, true, local, rail, youtube)).isEqualTo(TvTab.Fixed(TvDestination.SETTINGS))
    }

    @Test
    fun `the rail focuses its first item when no item is the shown tab`() {
        assertThat(railFocusTab(rail, local)).isEqualTo(local)
        assertThat(railFocusTab(rail, TvTab.Music(null))).isEqualTo(youtube)
        assertThat(railFocusTab(emptyList(), local)).isNull()
    }
}
