package io.github.aedev.flow.ui.tv.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.catalog.MusicTab
import io.github.aedev.flow.data.catalog.MusicTabs
import org.junit.Test

class TvMusicTabsStateTest {
    private val spotify = MusicSource.Plugin("nl.neerdael.spotify")
    private val youtube = MusicSource.Plugin("nl.neerdael.youtube-music")

    @Test
    fun `the remembered tab opens as soon as it exists`() {
        val state = resolveMusicTabs(tabs(youtube, spotify, settled = false), Remembered.Known(spotify), chosen = null)

        assertThat(state.selected).isEqualTo(spotify)
        assertThat(state.ready).isTrue()
    }

    @Test
    fun `a remembered tab still being checked waits instead of opening another`() {
        val state = resolveMusicTabs(tabs(youtube, settled = false), Remembered.Known(spotify), chosen = null)

        assertThat(state.selected).isNull()
        assertThat(state.ready).isFalse()
    }

    @Test
    fun `a remembered tab whose own account is still being checked waits, so its home loads once`() {
        val pending = MusicTabs(listOf(MusicTab(youtube, "YouTube Music", null, accountPending = true)), settled = false)

        val state = resolveMusicTabs(pending, Remembered.Known(youtube), chosen = null)

        assertThat(state.ready).isFalse()
        assertThat(state.selected).isNull()
    }

    @Test
    fun `nothing opens before the remembered tab is read`() {
        val state = resolveMusicTabs(tabs(youtube, settled = true), Remembered.Unknown, chosen = null)

        assertThat(state.ready).isFalse()
    }

    @Test
    fun `a remembered tab that is gone falls back to the first`() {
        val state = resolveMusicTabs(tabs(youtube, settled = true), Remembered.Known(spotify), chosen = null)

        assertThat(state.selected).isEqualTo(youtube)
        assertThat(state.ready).isTrue()
    }

    @Test
    fun `the chosen tab wins while it exists, and the start tab replaces it when it goes`() {
        assertThat(resolveMusicTabs(tabs(youtube, spotify, settled = true), Remembered.Known(spotify), youtube).selected)
            .isEqualTo(youtube)
        assertThat(resolveMusicTabs(tabs(spotify, settled = true), Remembered.Known(youtube), youtube).selected)
            .isEqualTo(spotify)
    }

    @Test
    fun `no tabs at all is ready with the empty music tab`() {
        val state = resolveMusicTabs(tabs(settled = true), Remembered.Known(null), chosen = null)

        assertThat(state.selected).isNull()
        assertThat(state.ready).isTrue()
    }

    private fun tabs(
        vararg sources: MusicSource,
        settled: Boolean,
    ) = MusicTabs(sources.map { MusicTab(it, label = it.key, iconRes = null) }, settled)
}
