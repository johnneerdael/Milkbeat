package io.github.aedev.flow.player.audio

import android.app.Application
import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MusicOutputRecoveryTest {
    @Test
    fun `stall recreates output at verified position and waits for actual frames before reporting playback`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            engine.onPrepare = output::newOutput
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            engine.positionMs = 180_000
            advanceTimeBy(5_000)
            pump()

            assertFalse(recovery.reportedPlayer.isPlaying)
            assertEquals(167_000L, recovery.reportedPlayer.currentPosition)
            assertEquals(1, engine.stops)
            assertEquals("first", engine.currentMediaItem!!.mediaId)
            assertEquals(2, engine.mediaItemCount)
            advanceTimeBy(500)
            pump()
            assertEquals(1, engine.prepares)
            assertFalse(recovery.reportedPlayer.isPlaying)

            output.head = 48_000
            engine.positionMs = 168_000
            advanceTimeBy(1_000)
            pump()
            assertTrue(recovery.reportedPlayer.isPlaying)
            assertFalse(recovery.isRecovering)
            assertEquals(168_000L, recovery.reportedPlayer.currentPosition)
            recovery.close()
        }

    @Test
    fun `persistent failure stops automatic retries and Play starts a fresh recovery`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            val warnings = mutableListOf<Boolean>()
            engine.onPrepare = output::newOutput
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime }, warnings::add)
            pump()
            repeat(40) {
                advanceTimeBy(1_000)
                pump()
            }

            assertEquals(2, engine.prepares)
            assertFalse(recovery.reportedPlayer.isPlaying)
            assertFalse(recovery.reportedPlayer.playWhenReady)
            assertEquals(Player.STATE_READY, recovery.reportedPlayer.playbackState)
            assertEquals(167_000L, recovery.reportedPlayer.currentPosition)
            assertEquals(listOf(true, true, false), warnings)
            val reads = output.reads
            advanceTimeBy(120_000)
            pump()
            assertEquals(2, engine.prepares)
            assertEquals(reads, output.reads)

            recovery.reportedPlayer.play()
            pump()
            advanceTimeBy(500)
            pump()
            assertEquals(3, engine.prepares)
            output.head = 48_000
            advanceTimeBy(1_000)
            pump()
            assertTrue(recovery.reportedPlayer.isPlaying)
            recovery.close()
        }

    @Test
    fun `pause during recovery cannot be undone by the pending restart`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            advanceTimeBy(5_000)
            pump()
            assertTrue(recovery.isRecovering)
            recovery.reportedPlayer.pause()
            pump()
            advanceTimeBy(30_000)
            pump()
            assertEquals(0, engine.prepares)
            assertFalse(engine.playWhenReady)
            assertFalse(recovery.reportedPlayer.isPlaying)
            recovery.close()
        }

    @Test
    fun `a changed track cancels recovery instead of seeking to the old item`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            advanceTimeBy(5_000)
            pump()
            assertTrue(recovery.isRecovering)
            engine.transition(1)
            pump()
            advanceTimeBy(1_000)
            pump()
            assertEquals(0, engine.prepares)
            assertEquals("second", recovery.reportedPlayer.currentMediaItem!!.mediaId)
            assertEquals(0L, engine.currentPosition)
            recovery.close()
        }

    @Test
    fun `ordinary source buffering does not trigger an output restart`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            engine.buffering()
            pump()
            advanceTimeBy(60_000)
            pump()
            assertEquals(0, engine.stops)
            assertEquals(0, engine.prepares)
            assertEquals(Player.STATE_BUFFERING, recovery.reportedPlayer.playbackState)
            recovery.close()
        }

    private fun TestScope.pump() {
        ShadowLooper.idleMainLooper()
        runCurrent()
        ShadowLooper.idleMainLooper()
        runCurrent()
    }

    private class TestOutput : AudioOutputProbe {
        override val changes = MutableStateFlow(1L)
        override val monitorable = true
        var head = 8_057_928L
        var reads = 0

        override fun read(): AudioOutputSample {
            reads++
            return AudioOutputSample(changes.value, head)
        }

        fun newOutput() {
            head = 0
            changes.value++
        }
    }
}
