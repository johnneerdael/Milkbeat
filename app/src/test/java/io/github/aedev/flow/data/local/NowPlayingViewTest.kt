package io.github.aedev.flow.data.local

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.NowPlayingView.STATIC
import io.github.aedev.flow.data.local.NowPlayingView.VIDEO
import io.github.aedev.flow.data.local.NowPlayingView.VISUALIZER
import org.junit.Test

class NowPlayingViewTest {
    @Test
    fun `the button steps from the visualizer to the video to the artwork and round again`() {
        assertThat(nextNowPlayingView(VISUALIZER, videoAvailable = true, visualizerAvailable = true)).isEqualTo(VIDEO)
        assertThat(nextNowPlayingView(VIDEO, videoAvailable = true, visualizerAvailable = true)).isEqualTo(STATIC)
        assertThat(nextNowPlayingView(STATIC, videoAvailable = true, visualizerAvailable = true)).isEqualTo(VISUALIZER)
    }

    @Test
    fun `a track without a video skips straight to the artwork`() {
        assertThat(nextNowPlayingView(VISUALIZER, videoAvailable = false, visualizerAvailable = true)).isEqualTo(STATIC)
        assertThat(nextNowPlayingView(STATIC, videoAvailable = false, visualizerAvailable = true)).isEqualTo(VISUALIZER)
    }

    @Test
    fun `without a visualizer the button moves between the video and the artwork only`() {
        assertThat(nextNowPlayingView(STATIC, videoAvailable = true, visualizerAvailable = false)).isEqualTo(VIDEO)
        assertThat(nextNowPlayingView(VIDEO, videoAvailable = true, visualizerAvailable = false)).isEqualTo(STATIC)
        assertThat(nextNowPlayingView(STATIC, videoAvailable = false, visualizerAvailable = false)).isEqualTo(STATIC)
    }

    @Test
    fun `confirmed video becomes selectable even when the remembered view is Video`() {
        assertThat(nextNowPlayingView(VISUALIZER, videoAvailable = true, visualizerAvailable = true)).isEqualTo(VIDEO)
        assertThat(nextNowPlayingView(STATIC, videoAvailable = true, visualizerAvailable = false)).isEqualTo(VIDEO)
    }

    @Test
    fun `the remembered view falls back when the track or device cannot show it`() {
        assertThat(shownNowPlayingView(VIDEO, videoShown = true, visualizerAvailable = true)).isEqualTo(VIDEO)
        assertThat(shownNowPlayingView(VIDEO, videoShown = false, visualizerAvailable = true)).isEqualTo(VISUALIZER)
        assertThat(shownNowPlayingView(VIDEO, videoShown = false, visualizerAvailable = false)).isEqualTo(STATIC)
        assertThat(shownNowPlayingView(VISUALIZER, videoShown = false, visualizerAvailable = false)).isEqualTo(STATIC)
        assertThat(shownNowPlayingView(STATIC, videoShown = false, visualizerAvailable = true)).isEqualTo(STATIC)
    }

    @Test
    fun `an unknown stored name reads as no choice`() {
        assertThat(NowPlayingView.fromName("VIDEO")).isEqualTo(VIDEO)
        assertThat(NowPlayingView.fromName("SLIDESHOW")).isNull()
        assertThat(NowPlayingView.fromName(null)).isNull()
    }
}
