package io.github.aedev.flow.ui.tv.screens.search

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.MusicSource
import org.junit.Test

class TvSearchSourcesTest {
    private val spotify = TvSearchSource.Music(MusicSource.Plugin("nl.neerdael.spotify"))
    private val local = TvSearchSource.Music(MusicSource.Local)

    @Test
    fun `until a chip is picked, Search follows the start chip as the tabs settle`() {
        val chips = listOf(local, spotify, TvSearchSource.Videos)

        assertThat(shownSearchSource(picked = null, chips = chips, start = spotify)).isEqualTo(spotify)
    }

    @Test
    fun `a picked chip stays while offered and gives way to the start chip when it goes`() {
        assertThat(
            shownSearchSource(TvSearchSource.Videos, listOf(spotify, TvSearchSource.Videos), spotify),
        ).isEqualTo(TvSearchSource.Videos)
        assertThat(shownSearchSource(local, listOf(spotify, TvSearchSource.Videos), spotify)).isEqualTo(spotify)
    }
}
