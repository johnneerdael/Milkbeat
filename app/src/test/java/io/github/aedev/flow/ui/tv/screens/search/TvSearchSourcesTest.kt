package io.github.aedev.flow.ui.tv.screens.search

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.MusicSource
import org.junit.Test

class TvSearchSourcesTest {
    private val spotify = MusicSource.Plugin("nl.neerdael.spotify")
    private val youtube = MusicSource.Plugin("nl.neerdael.youtube")
    private val local = MusicSource.Local

    @Test
    fun `until a chip is picked, Search follows the start chip as the tabs settle`() {
        val chips = listOf(local, spotify, youtube)

        assertThat(shownSearchSource(picked = null, chips = chips, start = spotify)).isEqualTo(spotify)
    }

    @Test
    fun `a picked chip stays while offered and gives way to the start chip when it goes`() {
        assertThat(shownSearchSource(youtube, listOf(spotify, youtube), spotify)).isEqualTo(youtube)
        assertThat(shownSearchSource(local, listOf(spotify, youtube), spotify)).isEqualTo(spotify)
    }

    @Test
    fun `no chip is shown while no tab can search`() {
        assertThat(shownSearchSource(picked = spotify, chips = emptyList(), start = null)).isNull()
    }
}
