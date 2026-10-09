package io.github.aedev.flow.ui.tv.music

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** State policy only: no claim that mocked callbacks establish actual rendered video on a device. */
class TvMusicVideoJoinStateTest {
    @Test
    fun `waiting animates only while the view is visible and playback is requested`() {
        val state = TvMusicVideoJoinState()
        assertThat(state.showIndicator).isFalse()
        state.playWhenReady = true
        assertThat(state.showIndicator).isFalse()
        state.visible = true
        assertThat(state.showIndicator).isTrue()
        state.playWhenReady = false
        assertThat(state.showIndicator).isFalse()
        state.playWhenReady = true
        assertThat(state.showIndicator).isTrue()
        state.visible = false
        assertThat(state.showIndicator).isFalse()
    }

    @Test
    fun `destroyed surface rejects stale frames and recreation waits independently`() {
        val state =
            TvMusicVideoJoinState().apply {
                visible = true
                playWhenReady = true
            }
        state.surfaceChanged(true)
        state.onRenderedFirstFrame()
        assertThat(state.showIndicator).isFalse()
        state.surfaceChanged(false)
        state.onRenderedFirstFrame()
        assertThat(state.showIndicator).isTrue()
        state.surfaceChanged(true)
        assertThat(state.showIndicator).isTrue()
    }

    @Test
    fun `new item cannot inherit the previous pictures completion and pause retains waiting`() {
        val state =
            TvMusicVideoJoinState().apply {
                visible = true
                playWhenReady = true
            }
        state.surfaceChanged(true)
        state.onRenderedFirstFrame()
        state.itemChanged()
        state.playWhenReady = false
        assertThat(state.showIndicator).isFalse()
        state.playWhenReady = true
        assertThat(state.showIndicator).isTrue()
    }
}
