package io.github.aedev.flow.player.audio

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
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
        scope: CoroutineScope,
        onHoldChanged: () -> Unit = {},
    ) {
        val rejections = mutableListOf<Pair<String, Long>>()
        val player = mockk<Player>(relaxed = true)
        val gate =
            MusicAudioStartGate(
                player,
                scope,
                { mediaId, position -> rejections.add(mediaId to position) },
                onHoldChanged,
                startTimeoutMs = 20_000,
            )

        var item: MediaItem? = null
        var audioSelected = false
        var state = Player.STATE_BUFFERING
        var playWhenReady = true
        var position = 0L
        var windowUid: Any = "window-0"
        private val currentTimeline: androidx.media3.common.Timeline = singleWindowTimeline()
        val eventTimeline: androidx.media3.common.Timeline
            get() = singleWindowTimeline()

        init {
            every { player.currentMediaItem } answers { item }
            every { player.currentTracks } answers {
                mockk<androidx.media3.common.Tracks> { every { isTypeSelected(C.TRACK_TYPE_AUDIO) } answers { audioSelected } }
            }
            every { player.playbackState } answers { state }
            every { player.playWhenReady } answers { playWhenReady }
            every { player.playbackSuppressionReason } returns Player.PLAYBACK_SUPPRESSION_REASON_NONE
            every { player.currentPosition } answers { position }
            every { player.currentTimeline } returns currentTimeline
            every { player.currentMediaItemIndex } returns 0
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

        fun monitor(flag: Int = Player.EVENT_PLAYBACK_STATE_CHANGED) {
            gate.onEvents(
                player,
                Player.Events(
                    androidx.media3.common.FlagSet
                        .Builder()
                        .add(flag)
                        .build(),
                ),
            )
        }

        fun advance(
            window: Any = windowUid,
            period: MediaPeriodId? = null,
            currentPeriod: MediaPeriodId? = period,
        ) {
            val eventTimeline = singleWindowTimeline(window)
            gate.onAudioPositionAdvancing(
                AnalyticsListener.EventTime(0L, eventTimeline, 0, period, 0L, eventTimeline, 0, currentPeriod, 0L, 0L),
                0L,
            )
        }

        private fun singleWindowTimeline(uid: Any? = null): androidx.media3.common.Timeline =
            object : androidx.media3.common.Timeline() {
                override fun getWindowCount(): Int = 1

                override fun getWindow(
                    windowIndex: Int,
                    window: androidx.media3.common.Timeline.Window,
                    defaultPositionProjectionUs: Long,
                ): androidx.media3.common.Timeline.Window {
                    window.uid = uid ?: this@Fixture.windowUid
                    return window
                }

                override fun getPeriodCount(): Int = 1

                override fun getPeriod(
                    periodIndex: Int,
                    period: androidx.media3.common.Timeline.Period,
                    setIds: Boolean,
                ): androidx.media3.common.Timeline.Period = period

                override fun getIndexOfPeriod(uid: Any): Int = 0

                override fun getUidOfPeriod(periodIndex: Int): Any = "period"
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
    fun `a gapless queue change confirms the next item without another advancement`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-1")
            fixture.state = Player.STATE_READY
            fixture.audioSelected = true
            fixture.monitor()
            fixture.advance()
            assertTrue(fixture.gate.confirmed("song-1"))

            // The same output keeps playing into the next window; Media3 reports advancement
            // once, when output first starts, not again per media item.
            fixture.open("song-2")
            fixture.windowUid = "second-window"
            fixture.monitor(Player.EVENT_TIMELINE_CHANGED)
            assertTrue(fixture.gate.confirmed("song-2"))
            assertNull(fixture.gate.pendingHold("song-2"))

            advanceTimeBy(20_000)
            runCurrent()
            assertTrue(fixture.rejections.isEmpty())
        }

    @Test
    fun `a seek ends continuous output so the replacement confirms its own start`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-1")
            fixture.state = Player.STATE_READY
            fixture.audioSelected = true
            fixture.monitor()
            fixture.advance()
            assertTrue(fixture.gate.confirmed("song-1"))

            val position = positionInfo("song-1")
            fixture.gate.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_SEEK)
            fixture.open("song-2")
            fixture.windowUid = "second-window"
            fixture.monitor(Player.EVENT_TIMELINE_CHANGED)
            assertFalse(fixture.gate.confirmed("song-2"))
            assertNotNull(fixture.gate.pendingHold("song-2"))

            fixture.advance()
            assertTrue(fixture.gate.confirmed("song-2"))
            assertNull(fixture.gate.pendingHold("song-2"))
        }

    @Test
    fun `a rebuilt audio output does not confirm the next item by continuity`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-1")
            fixture.state = Player.STATE_READY
            fixture.audioSelected = true
            fixture.monitor()
            fixture.advance()
            assertTrue(fixture.gate.confirmed("song-1"))

            fixture.gate.onAudioTrackInitialized(
                AnalyticsListener.EventTime(0L, fixture.eventTimeline, 0, null, 0L, fixture.eventTimeline, 0, null, 0L, 0L),
                AudioSink.AudioTrackConfig(androidx.media3.common.C.ENCODING_PCM_16BIT, 44_100, 12, false, false, 4_096),
            )
            fixture.open("song-2")
            fixture.windowUid = "second-window"
            fixture.monitor(Player.EVENT_TIMELINE_CHANGED)
            assertFalse(fixture.gate.confirmed("song-2"))
            assertNotNull(fixture.gate.pendingHold("song-2"))
        }

    private fun positionInfo(mediaId: String): Player.PositionInfo {
        val item =
            MediaItem
                .Builder()
                .setMediaId(mediaId)
                .setUri("music://$mediaId")
                .build()
        return Player.PositionInfo("window-0", 0, item, "period", 0, 0L, 0L, C.INDEX_UNSET, C.INDEX_UNSET)
    }

    @Test
    fun `a callback from an earlier window cannot confirm its replacement`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("song-9")
            fixture.windowUid = "first"
            fixture.state = Player.STATE_READY
            // A selected but not-yet-advancing track: only advancement may confirm.
            fixture.audioSelected = true
            fixture.monitor()
            fixture.advance("other")
            assertFalse(fixture.gate.confirmed("song-9"))
            fixture.advance("first")
            assertTrue(fixture.gate.confirmed("song-9"))
        }

    @Test
    fun `a stale period cannot confirm a retry in the same window`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("repeated")
            fixture.monitor()
            fixture.gate.sourceReplaced("repeated")
            fixture.monitor()

            val previous = MediaPeriodId("period", 1L)
            val current = MediaPeriodId("period", 2L)
            fixture.advance(period = previous, currentPeriod = current)
            assertFalse(fixture.gate.confirmed("repeated"))
            fixture.advance(period = current)
            assertTrue(fixture.gate.confirmed("repeated"))
        }

    @Test
    fun `a duplicate recording in a different queue window must confirm its own output`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("duplicate")
            fixture.monitor()
            fixture.advance()
            assertTrue(fixture.gate.confirmed("duplicate"))

            val previous = fixture.windowUid
            fixture.windowUid = "second-window"
            // Seeking to the duplicate window flushes the output, so continuity cannot confirm it.
            val position = positionInfo("duplicate")
            fixture.gate.onPositionDiscontinuity(position, position, Player.DISCONTINUITY_REASON_SEEK)
            fixture.monitor(Player.EVENT_TIMELINE_CHANGED)
            fixture.advance(window = previous)
            assertFalse(fixture.gate.confirmed("duplicate"))
            fixture.advance()
            assertTrue(fixture.gate.confirmed("duplicate"))
        }

    @Test
    fun `pause while buffering cancels recovery and resume starts a full timeout`() =
        runTest {
            val fixture = Fixture(backgroundScope)
            fixture.open("paused")
            fixture.monitor()
            runCurrent()
            advanceTimeBy(15_000)
            fixture.playWhenReady = false
            fixture.monitor(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            advanceTimeBy(25_000)
            runCurrent()
            assertTrue(fixture.rejections.isEmpty())

            fixture.playWhenReady = true
            fixture.monitor(Player.EVENT_PLAY_WHEN_READY_CHANGED)
            advanceTimeBy(19_999)
            runCurrent()
            assertTrue(fixture.rejections.isEmpty())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("paused" to 0L), fixture.rejections)
        }

    @Test
    fun `confirmation refreshes the exposed state without another player event`() =
        runTest {
            var refreshes = 0
            val fixture = Fixture(backgroundScope, onHoldChanged = { refreshes++ })
            fixture.open("song-10")
            fixture.monitor()
            val scheduled = refreshes
            fixture.advance()
            assertTrue(fixture.gate.confirmed("song-10"))
            assertTrue(refreshes > scheduled)
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
