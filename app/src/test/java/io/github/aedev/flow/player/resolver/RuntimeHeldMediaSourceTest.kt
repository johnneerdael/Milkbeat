package io.github.aedev.flow.player.resolver

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.PluginPlaybackLease
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RuntimeHeldMediaSourceTest {
    private fun child(): MediaSource =
        mockk<MediaSource>(relaxed = true).also {
            every { it.mediaItem } returns
                MediaItem.fromUri("https://fixture.example/recording")
        }

    private val caller = MediaSource.MediaSourceCaller { _, _ -> }

    @Test
    fun `prepared source retains its provider until release without rewriting its accepted item`() {
        val child = child()
        val holds = AtomicInteger()
        val entered = CountDownLatch(1)
        val source =
            RuntimeHeldMediaSource(child) {
                PluginPlaybackLease(child, { 1L }, { holds.incrementAndGet() }, { holds.decrementAndGet() }).also { entered.countDown() }
            }
        source.prepareSource(caller, PlayerId.UNSET, mockk<androidx.media3.exoplayer.upstream.BandwidthMeter>(relaxed = true))
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
        repeat(100) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (holds.get() == 1) Thread.sleep(5)
        }
        verify(exactly = 1) { child.prepareSource(any(), any<PlayerId>(), any<androidx.media3.exoplayer.upstream.BandwidthMeter>()) }
        assertThat(source.mediaItem).isSameInstanceAs(child.mediaItem)
        assertThat(holds.get()).isEqualTo(1)
        source.releaseSource(caller)
        assertThat(holds.get()).isEqualTo(0)
    }

    @Test
    fun `a queued source canceled before lease acquisition prepares no child and retains no runtime`() {
        val child = child()
        val entered = CountDownLatch(1)
        val pending = CompletableDeferred<Unit>()
        val holds = AtomicInteger()
        val source =
            RuntimeHeldMediaSource(child) {
                entered.countDown()
                pending.await()
                PluginPlaybackLease(child, { 1L }, { holds.incrementAndGet() }, { holds.decrementAndGet() })
            }
        source.prepareSource(caller, PlayerId.UNSET, mockk<androidx.media3.exoplayer.upstream.BandwidthMeter>(relaxed = true))
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
        source.releaseSource(caller)
        pending.complete(Unit)
        repeat(10) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        verify(exactly = 0) { child.prepareSource(any(), any<PlayerId>(), any<androidx.media3.exoplayer.upstream.BandwidthMeter>()) }
        assertThat(holds.get()).isEqualTo(0)
    }
}
