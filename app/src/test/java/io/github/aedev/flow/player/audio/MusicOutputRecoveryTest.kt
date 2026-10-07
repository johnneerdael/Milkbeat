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

    @Test
    fun `seek during restart delay prepares the requested position without a stale restart`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            engine.onPrepare = output::newOutput
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            advanceTimeBy(5_000)
            pump()
            assertTrue(recovery.isRecovering)

            recovery.reportedPlayer.seekTo(240_000)
            pump()
            assertEquals(1, engine.prepares)
            assertEquals(240_000L, recovery.reportedPlayer.currentPosition)
            assertFalse(recovery.isRecovering)
            advanceFrames(engine, output, 30)
            assertEquals(1, engine.prepares)
            assertTrue(recovery.reportedPlayer.isPlaying)
            assertEquals(270_000L, recovery.reportedPlayer.currentPosition)
            recovery.close()
        }

    @Test
    fun `Stop during restart delay cancels the attempt and output monitoring`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            advanceTimeBy(5_000)
            pump()
            assertTrue(recovery.isRecovering)

            recovery.reportedPlayer.stop()
            pump()
            val reads = output.reads
            advanceTimeBy(60_000)
            pump()
            assertEquals(0, engine.prepares)
            assertFalse(recovery.isRecovering)
            assertFalse(recovery.reportedPlayer.isPlaying)
            assertEquals(Player.STATE_IDLE, recovery.reportedPlayer.playbackState)
            assertEquals(reads, output.reads)
            recovery.close()
        }

    @Test
    fun `focus suppression before or after prepare prevents recovery from resuming playback`() =
        runTest {
            for (afterPrepare in listOf(false, true)) {
                val engine = OutputTestPlayer()
                val output = TestOutput()
                engine.onPrepare = output::newOutput
                val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
                pump()
                advanceTimeBy(5_000)
                pump()
                if (afterPrepare) {
                    advanceTimeBy(500)
                    pump()
                }

                engine.suppress()
                pump()
                val reads = output.reads
                advanceTimeBy(60_000)
                pump()
                assertEquals(if (afterPrepare) 1 else 0, engine.prepares)
                assertFalse(engine.playWhenReady)
                assertFalse(recovery.isRecovering)
                assertFalse(recovery.reportedPlayer.isPlaying)
                assertEquals(167_000L, recovery.reportedPlayer.currentPosition)
                assertEquals(reads, output.reads)
                recovery.close()
            }
        }

    @Test
    fun `focus loss before or after prepare cancels the automatic restart`() =
        runTest {
            for (afterPrepare in listOf(false, true)) {
                val engine = OutputTestPlayer()
                val output = TestOutput()
                engine.onPrepare = output::newOutput
                val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
                pump()
                advanceTimeBy(5_000)
                pump()
                if (afterPrepare) {
                    advanceTimeBy(500)
                    pump()
                }

                engine.loseFocus()
                pump()
                advanceTimeBy(60_000)
                pump()
                assertEquals(if (afterPrepare) 1 else 0, engine.prepares)
                assertFalse(engine.playWhenReady)
                assertFalse(recovery.isRecovering)
                assertFalse(recovery.reportedPlayer.isPlaying)
                recovery.close()
            }
        }

    @Test
    fun `close during restart delay cancels preparation and all later probe reads`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            advanceTimeBy(5_000)
            pump()
            assertTrue(recovery.isRecovering)
            recovery.close()
            val reads = output.reads
            output.newOutput()
            advanceTimeBy(60_000)
            pump()
            assertEquals(0, engine.prepares)
            assertEquals(reads, output.reads)
        }

    @Test
    fun `healthy pause suspends probe reads and resume monitors progressing PCM`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            advanceFrames(engine, output, 3)
            recovery.reportedPlayer.pause()
            pump()
            val reads = output.reads
            advanceTimeBy(60_000)
            pump()
            assertEquals(reads, output.reads)
            assertEquals(0, engine.stops)

            recovery.reportedPlayer.play()
            pump()
            advanceFrames(engine, output, 10)
            assertTrue(output.reads > reads)
            assertTrue(recovery.reportedPlayer.isPlaying)
            assertEquals(0, engine.prepares)
            recovery.close()
        }

    @Test
    fun `nonmonitorable output suspends reads and PCM transitions restart monitoring`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput().apply { monitorable = false }
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            advanceTimeBy(120_000)
            pump()
            assertEquals(0, output.reads)
            assertEquals(0, engine.stops)

            output.monitorable = true
            output.newOutput()
            pump()
            advanceFrames(engine, output, 10)
            assertTrue(output.reads > 0)
            assertEquals(0, engine.stops)
            output.monitorable = false
            output.newOutput()
            pump()
            val reads = output.reads
            advanceTimeBy(60_000)
            pump()
            assertEquals(reads, output.reads)

            output.monitorable = true
            output.newOutput()
            pump()
            advanceTimeBy(5_000)
            pump()
            assertTrue(recovery.isRecovering)
            assertEquals(1, engine.stops)
            recovery.close()
        }

    @Test
    fun `thirty seconds of actual frame progress rearms an exhausted automatic retry budget`() =
        runTest {
            val engine = OutputTestPlayer()
            val output = TestOutput()
            engine.onPrepare = output::newOutput
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            repeat(2) {
                advanceTimeBy(5_000)
                pump()
                assertTrue(recovery.isRecovering)
                advanceTimeBy(500)
                pump()
                advanceFrames(engine, output, 1)
                assertTrue(recovery.reportedPlayer.isPlaying)
            }
            assertEquals(2, engine.prepares)

            advanceFrames(engine, output, 30)
            advanceTimeBy(5_000)
            pump()
            assertTrue(recovery.isRecovering)
            advanceTimeBy(500)
            pump()
            assertEquals(3, engine.prepares)
            assertTrue(engine.playWhenReady)
            recovery.close()
        }

    @Test
    fun `prepare that remains buffering reaches the deadline and stops after two attempts`() =
        runTest {
            val engine = OutputTestPlayer().apply { prepareReady = false }
            val output = TestOutput()
            engine.onPrepare = output::newOutput
            val recovery = MusicOutputRecovery(engine, backgroundScope, output, { testScheduler.currentTime })
            pump()
            advanceTimeBy(5_500)
            pump()
            assertEquals(1, engine.prepares)
            assertEquals(Player.STATE_BUFFERING, engine.playbackState)
            assertTrue(recovery.isRecovering)
            advanceTimeBy(15_000)
            pump()
            assertEquals(2, engine.stops)
            advanceTimeBy(500)
            pump()
            assertEquals(2, engine.prepares)
            advanceTimeBy(15_000)
            pump()
            assertFalse(recovery.isRecovering)
            assertFalse(engine.playWhenReady)
            assertEquals(Player.STATE_READY, recovery.reportedPlayer.playbackState)
            assertEquals(167_000L, recovery.reportedPlayer.currentPosition)
            val reads = output.reads
            advanceTimeBy(60_000)
            pump()
            assertEquals(2, engine.prepares)
            assertEquals(reads, output.reads)
            recovery.close()
        }

    private fun TestScope.advanceFrames(
        engine: OutputTestPlayer,
        output: TestOutput,
        seconds: Int,
    ) {
        repeat(seconds) {
            output.head += 48_000
            engine.positionMs += 1_000
            advanceTimeBy(1_000)
            pump()
        }
    }

    private fun TestScope.pump() {
        ShadowLooper.idleMainLooper()
        runCurrent()
        ShadowLooper.idleMainLooper()
        runCurrent()
    }

    private class TestOutput : AudioOutputProbe {
        override val changes = MutableStateFlow(1L)
        override var monitorable = true
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
