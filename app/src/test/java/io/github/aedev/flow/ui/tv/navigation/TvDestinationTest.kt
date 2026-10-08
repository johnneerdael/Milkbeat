package io.github.aedev.flow.ui.tv.navigation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TvDestinationTest {
    @Test
    fun `fixed destinations follow the music tabs in a stable order`() {
        assertThat(TvDestination.fixed)
            .containsExactly(
                TvDestination.SEARCH,
                TvDestination.LIBRARY,
                TvDestination.SETTINGS,
            ).inOrder()
    }

    @Test
    fun `route lookup falls back to the music start tab`() {
        assertThat(TvDestination.start).isEqualTo(TvDestination.MUSIC)
        assertThat(TvDestination.fromRoute("search")).isEqualTo(TvDestination.SEARCH)
        assertThat(TvDestination.fromRoute("missing")).isEqualTo(TvDestination.MUSIC)
        assertThat(TvDestination.fromRoute(null)).isEqualTo(TvDestination.MUSIC)
    }
}
