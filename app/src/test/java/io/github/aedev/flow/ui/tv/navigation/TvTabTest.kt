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
        val owner = ownerDestination(listOf("music", "search", "catalog/artist"))
        assertThat(owner).isEqualTo(TvDestination.SEARCH)
        assertThat(shownRailTab(owner, rail, local)).isEqualTo(search)
    }

    @Test
    fun `a music page shows the selected music tab, whatever the shell remembered`() {
        // The album a music tab opened, restored under Settings' Back: the rail shows the music tab, not Settings.
        assertThat(ownerDestination(listOf("music", "catalog/album"))).isEqualTo(TvDestination.MUSIC)
        assertThat(shownRailTab(TvDestination.MUSIC, rail, youtube)).isEqualTo(youtube)
    }

    @Test
    fun `a top-level route is its own tab and the folders settings detail belongs to Settings`() {
        assertThat(ownerDestination(listOf("music", "search"))).isEqualTo(TvDestination.SEARCH)
        assertThat(ownerDestination(listOf("music", TvRoutes.MUSIC_FOLDERS_SETTINGS))).isEqualTo(TvDestination.SETTINGS)
        assertThat(ownerDestination(emptyList())).isEqualTo(TvDestination.MUSIC)
        assertThat(shownRailTab(TvDestination.SETTINGS, rail + TvTab.Fixed(TvDestination.SETTINGS), youtube))
            .isEqualTo(TvTab.Fixed(TvDestination.SETTINGS))
    }

    @Test
    fun `an owner the rail does not have shows the music tab`() {
        assertThat(shownRailTab(TvDestination.LIBRARY, rail, youtube)).isEqualTo(youtube)
    }

    @Test
    fun `pressing the tab that owns the page returns to its root instead of restoring the page`() {
        assertThat(tabNavigation(TvDestination.SEARCH, owner = TvDestination.SEARCH)).isEqualTo(TvTabNavigation.POP_TO_ROOT)
        assertThat(tabNavigation(TvDestination.MUSIC, owner = TvDestination.MUSIC)).isEqualTo(TvTabNavigation.POP_TO_ROOT)
    }

    @Test
    fun `the music tabs are popped to from any tab and other tabs are switched to`() {
        assertThat(tabNavigation(TvDestination.MUSIC, owner = TvDestination.SETTINGS)).isEqualTo(TvTabNavigation.POP_TO_ROOT)
        assertThat(tabNavigation(TvDestination.SETTINGS, owner = TvDestination.MUSIC)).isEqualTo(TvTabNavigation.SWITCH)
        assertThat(tabNavigation(TvDestination.LIBRARY, owner = TvDestination.SEARCH)).isEqualTo(TvTabNavigation.SWITCH)
    }

    @Test
    fun `Back history keeps only tabs the rail still has`() {
        val spotify = TvTab.Music(MusicSource.Plugin("nl.neerdael.spotify"))

        assertThat(prunedTabHistory(listOf(spotify, search, TvTab.Music(null), local), rail)).containsExactly(search, local).inOrder()
    }

    @Test
    fun `the rail focuses its first item when no item is the shown tab`() {
        assertThat(railFocusTab(rail, local)).isEqualTo(local)
        assertThat(railFocusTab(rail, TvTab.Music(null))).isEqualTo(youtube)
        assertThat(railFocusTab(emptyList(), local)).isNull()
    }
}
