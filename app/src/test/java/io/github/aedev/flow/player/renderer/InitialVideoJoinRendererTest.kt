package io.github.aedev.flow.player.renderer

import android.app.Application
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.Renderer
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.VideoJoinFixture
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class InitialVideoJoinRendererTest {
    @Test
    fun `readiness bypass ends at initial prebuffer and later buffering uses the real renderer`() {
        val f = VideoJoinFixture()
        val delegate = mockk<Renderer>(relaxed = true)
        every { delegate.isReady } returns false
        val renderer = InitialVideoJoinRenderer(delegate, f.gate)
        assertThat(renderer.isReady).isFalse()
        f.gate.begin()
        assertThat(renderer.isReady).isTrue()
        f.load(10_000, 20_000)
        assertThat(renderer.isReady).isFalse()
        every { delegate.isReady } returns true
        assertThat(renderer.isReady).isTrue()
    }

    @Test
    fun `render stream errors and renderer commands retain the delegate behavior`() {
        val f = VideoJoinFixture()
        f.gate.begin()
        val delegate = mockk<Renderer>(relaxed = true)
        val renderer = InitialVideoJoinRenderer(delegate, f.gate)
        val decoderFailure =
            ExoPlaybackException.createForUnexpected(
                IllegalStateException("decoder"),
                PlaybackException.ERROR_CODE_UNSPECIFIED,
            )
        val sourceFailure = IOException("source")
        every { delegate.render(any(), any()) } throws decoderFailure
        every { delegate.maybeThrowStreamError() } throws sourceFailure
        assertThat(assertThrows(ExoPlaybackException::class.java) { renderer.render(10_000, 20_000) }).isSameInstanceAs(decoderFailure)
        assertThat(assertThrows(IOException::class.java) { renderer.maybeThrowStreamError() }).isSameInstanceAs(sourceFailure)
        renderer.resetPosition(10_000, true)
        renderer.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null)
        verify(exactly = 1) { delegate.resetPosition(10_000, true) }
        verify(exactly = 1) { delegate.handleMessage(Renderer.MSG_SET_VIDEO_OUTPUT, null) }
    }
}
