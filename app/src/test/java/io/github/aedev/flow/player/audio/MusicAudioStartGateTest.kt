package io.github.aedev.flow.player.audio

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MusicAudioStartGateTest {
    private class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
    ) {
        val rejections = mutableListOf<Pair<String, Long>>()
        val player = mockk<Player>(relaxed = true)
        val gate = MusicAudioStartGate(player, scope, { mediaId, position -> rejections.add(mediaId to position) }, startTimeoutMs = 20_000)

        var item: MediaItem? = null
        var audioSelected = false
        var state = Player.STATE_BUFFERING
        var playWhenReady = true
        var position = 0L

        init {
            every { player.currentMediaItem } answers { item }
            every { player.currentTracks } answers {
                mockk<androidx.media3.common.Tracks> { every { isTypeSelected(C.TRACK_TYPE_AUDIO) } answers { audioSelected } }
            }
            every { player.playbackState } answers { state }
            every { player.playWhenReady } answers { playWhenReady }
            every { player.playbackSuppressionReason } returns Player.PLAYBACK_SUPPRESSION_REASON_NONE
            every { player.currentPosition } answers { position }
        }

        fun open(mediaId: String) {
            item =
                MediaItem
                    .Builder()
                    .setMediaId(mediaId)
                    .setUri("music://$mediaId")
                    .build()
            position = 0
        }

        fun monitor() {
            gate.onEvents(
                player,
                Player.Events(
                    androidx.media3.common.FlagSet
                        .Builder()
                        .add(Player.EVENT_PLAYBACK_STATE_CHANGED)
                        .build(),
                ),
            )
        }

        fun advance() {
            gate.onAudioPositionAdvancing(mockk(relaxed = true), 0L)
        }
    }

    @Test
    fun `audio position advancement confirms the open and clears the pending hold`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-1")
            fixture.state = Player.STATE_READY
            fixture.monitor()
            assertNotNull(fixture.gate.pendingHold("song-1"))
            assertFalse(fixture.gate.confirmed("song-1"))

            fixture.advance()
            assertTrue(fixture.gate.confirmed("song-1"))
            assertNull(fixture.gate.pendingHold("song-1"))
            assertTrue(fixture.rejections.isEmpty())
        }

    @Test
    fun `a Ready source with no selected audio track is rejected at the same position`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-2")
            fixture.position = 42_000
            fixture.state = Player.STATE_READY
            fixture.audioSelected = false
            fixture.monitor()
            runCurrent()
            assertEquals(listOf("song-2" to 42_000L), fixture.rejections)
        }

    @Test
    fun `a selected track that never produces output is rejected after the bound`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-3")
            fixture.state = Player.STATE_READY
            fixture.audioSelected = true
            fixture.monitor()
            runCurrent()
            assertTrue(fixture.rejections.isEmpty())
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals(listOf("song-3" to 0L), fixture.rejections)
            assertNull(fixture.gate.pendingHold("song-3"))
        }

    @Test
    fun `an advancement confirms the open that is current, not a previous one`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-4")
            fixture.monitor()
            fixture.advance()
            assertTrue(fixture.gate.confirmed("song-4"))

            fixture.open("song-5")
            fixture.gate.attemptChanged()
            fixture.monitor()
            // Audio advancing while song-5 is current confirms song-5 only; song-4's confirmation
            // cannot leak into the new attempt.
            fixture.advance()
            assertTrue(fixture.gate.confirmed("song-5"))
            assertNull(fixture.gate.pendingHold("song-5"))
            assertTrue(fixture.rejections.isEmpty())
        }

    @Test
    fun `a replaced source re-confirms its audio start`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-6")
            fixture.monitor()
            fixture.advance()
            assertTrue(fixture.gate.confirmed("song-6"))

            fixture.gate.sourceReplaced("song-6")
            assertFalse(fixture.gate.confirmed("song-6"))
            fixture.state = Player.STATE_READY
            fixture.audioSelected = false
            fixture.monitor()
            runCurrent()
            assertEquals(listOf("song-6" to 0L), fixture.rejections)
        }

    @Test
    fun `a paused open stays pending without a deadline`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-7")
            fixture.playWhenReady = false
            fixture.monitor()
            runCurrent()
            advanceTimeBy(120_000)
            runCurrent()
            assertTrue(fixture.rejections.isEmpty())
            assertNotNull(fixture.gate.pendingHold("song-7"))
        }

    @Test
    fun `an ended attempt that never sounded audio is rejected`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-8")
            fixture.position = 5_000
            fixture.state = Player.STATE_ENDED
            fixture.monitor()
            runCurrent()
            assertEquals(listOf("song-8" to 5_000L), fixture.rejections)
        }
}
