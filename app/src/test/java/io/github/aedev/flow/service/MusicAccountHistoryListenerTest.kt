package io.github.aedev.flow.service

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class MusicAccountHistoryListenerTest {
    @Test
    fun `long playing sets report before finishing and every thirty playing seconds`() =
        runTest {
            val fixture = fixture()
            fixture.player.positionMs = 0
            pump()
            advanceTimeBy(29_999)
            fixture.player.positionMs = 29_999
            pump()
            assertTrue(fixture.reports.isEmpty())
            advanceTimeBy(1)
            fixture.player.positionMs = 30_000
            pump()
            assertEquals(listOf(30_000L), fixture.reports.map { it.second.playedMs })
            assertTrue(
                fixture.reports
                    .single()
                    .second.progress,
            )
            advanceTimeBy(30_000)
            fixture.player.positionMs = 60_000
            pump()
            assertEquals(listOf(30_000L, 60_000L), fixture.reports.map { it.second.positionMs })
            fixture.listener.close()
        }

    @Test
    fun `pause reports actual seek position and suspends cadence without counting paused time`() =
        runTest {
            val fixture = fixture()
            pump()
            advanceTimeBy(31_000)
            fixture.player.positionMs = 31_000
            pump()
            fixture.player.seekTo(360_000)
            pump()
            fixture.player.pause()
            pump()
            val paused = fixture.reports.last().second
            assertEquals(31_000L, paused.playedMs)
            assertEquals(360_000L, paused.positionMs)
            val count = fixture.reports.size
            advanceTimeBy(120_000)
            pump()
            assertEquals(count, fixture.reports.size)
            fixture.player.play()
            pump()
            advanceTimeBy(30_000)
            fixture.player.positionMs = 390_000
            pump()
            assertEquals(
                61_000L,
                fixture.reports
                    .last()
                    .second.playedMs,
            )
            assertEquals(
                390_000L,
                fixture.reports
                    .last()
                    .second.positionMs,
            )
            fixture.listener.close()
        }

    @Test
    fun `transition pins outgoing seek position instead of new track zero`() =
        runTest {
            val fixture = fixture()
            pump()
            advanceTimeBy(31_000)
            fixture.player.positionMs = 31_000
            pump()
            fixture.player.seekTo(360_000)
            pump()
            fixture.player.transition(1)
            pump()
            val outgoing = fixture.reports.last { !it.second.progress }
            assertEquals("first", outgoing.first.videoId)
            assertEquals(360_000L, outgoing.second.positionMs)
            assertEquals(31_000L, outgoing.second.playedMs)
            advanceTimeBy(30_000)
            fixture.player.positionMs = 30_000
            pump()
            assertEquals(
                "second",
                fixture.reports
                    .last()
                    .first.videoId,
            )
            assertEquals(
                30_000L,
                fixture.reports
                    .last()
                    .second.playedMs,
            )
            fixture.listener.close()
        }

    @Test
    fun `buffering stops reports and close flushes once after the service scope is cancelled`() =
        runTest {
            val fixture = fixture()
            pump()
            advanceTimeBy(35_000)
            fixture.player.positionMs = 35_000
            fixture.player.buffering()
            pump()
            val count = fixture.reports.size
            advanceTimeBy(120_000)
            pump()
            assertEquals(count, fixture.reports.size)
            backgroundScope.cancel()
            fixture.listener.close()
            fixture.listener.close()
            assertEquals(1, fixture.reports.count { !it.second.progress })
            assertEquals(
                35_000L,
                fixture.reports
                    .last()
                    .second.playedMs,
            )
            assertEquals(
                35_000L,
                fixture.reports
                    .last()
                    .second.positionMs,
            )
            assertFalse(
                fixture.reports
                    .last()
                    .second.progress,
            )
        }

    @Test
    fun `short tracks qualify at half their duration while skipped tracks stay out`() =
        runTest {
            val fixture = fixture(durationMs = 20_000)
            pump()
            advanceTimeBy(9_999)
            pump()
            assertTrue(fixture.reports.isEmpty())
            advanceTimeBy(1)
            pump()
            assertEquals(
                10_000L,
                fixture.reports
                    .single()
                    .second.playedMs,
            )
            fixture.player.transition(1)
            pump()
            advanceTimeBy(1_000)
            fixture.listener.close()
            assertFalse(fixture.reports.any { it.first.videoId == "second" })
        }

    @Test
    fun `seeks retain a session while replay of the same recording starts another`() =
        runTest {
            val fixture = fixture()
            pump()
            advanceTimeBy(30_000)
            pump()
            val first =
                fixture.reports
                    .single()
                    .second.playbackSessionId
            fixture.player.seekTo(360_000)
            pump()
            assertTrue(fixture.reports.all { it.second.playbackSessionId == first })
            fixture.player.transition(1)
            pump()
            fixture.player.transition(0)
            pump()
            advanceTimeBy(30_000)
            pump()
            val replay = fixture.reports.last()
            assertEquals("first", replay.first.videoId)
            assertEquals(30_000L, replay.second.playedMs)
            assertNotEquals(first, replay.second.playbackSessionId)
            fixture.listener.close()
        }

    private fun TestScope.fixture(durationMs: Long = 600_000): Fixture {
        val player = HistoryTestPlayer(durationMs) { testScheduler.currentTime }
        val reports = mutableListOf<Pair<MusicTrack, AccountListenProgress>>()
        val listener =
            MusicAccountHistoryListener(
                player,
                backgroundScope,
                { id -> MusicTrack(id, id, "artist", "", (durationMs / 1_000).toInt()) },
                { track, progress -> reports += track to progress },
                { testScheduler.currentTime },
            )
        return Fixture(player, listener, reports)
    }

    private fun TestScope.pump() {
        ShadowLooper.idleMainLooper()
        runCurrent()
        ShadowLooper.idleMainLooper()
    }

    private data class Fixture(
        val player: HistoryTestPlayer,
        val listener: MusicAccountHistoryListener,
        val reports: MutableList<Pair<MusicTrack, AccountListenProgress>>,
    )

    // Snapshot positions belong to their state. A PositionSupplier reading a mutable property
    // would also change the outgoing state's position when the next item resets it to zero.
    private class HistoryTestPlayer(
        durationMs: Long,
        private val nowMs: () -> Long,
    ) : SimpleBasePlayer(Looper.getMainLooper()) {
        private var value =
            State
                .Builder()
                .setAvailableCommands(
                    Player.Commands
                        .Builder()
                        .addAllCommands()
                        .build(),
                ).setPlaylist(
                    listOf("first", "second").map { id ->
                        MediaItemData
                            .Builder(id)
                            .setMediaItem(
                                MediaItem
                                    .Builder()
                                    .setMediaId(id)
                                    .setUri("file:///$id.wav")
                                    .build(),
                            ).setDurationUs(durationMs * 1_000)
                            .build()
                    },
                ).setCurrentMediaItemIndex(0)
                .setPlaybackState(Player.STATE_READY)
                .setPlayWhenReady(true, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setContentPositionMs(position(0, true))
                .build()

        var positionMs = 0L
            set(position) {
                field = position
                value = value.buildUpon().setContentPositionMs(position(position, isPlaying)).build()
                invalidateState()
            }

        override fun getState(): State = value

        fun transition(index: Int) {
            value =
                value
                    .buildUpon()
                    .setCurrentMediaItemIndex(index)
                    .setContentPositionMs(position(0, isPlaying))
                    .build()
            invalidateState()
        }

        fun buffering() {
            value =
                value
                    .buildUpon()
                    .setContentPositionMs(currentPosition)
                    .setPlaybackState(Player.STATE_BUFFERING)
                    .build()
            invalidateState()
        }

        override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
            val position = currentPosition
            value =
                value
                    .buildUpon()
                    .setPlayWhenReady(playWhenReady, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                    .setContentPositionMs(position(position, playWhenReady && playbackState == Player.STATE_READY))
                    .build()
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        override fun handleSeek(
            mediaItemIndex: Int,
            positionMs: Long,
            seekCommand: Int,
        ): ListenableFuture<*> {
            value =
                value
                    .buildUpon()
                    .setCurrentMediaItemIndex(mediaItemIndex)
                    .setContentPositionMs(position(positionMs, isPlaying))
                    .build()
            invalidateState()
            return Futures.immediateVoidFuture()
        }

        private fun position(
            positionMs: Long,
            playing: Boolean,
        ): PositionSupplier {
            val startMs = nowMs()
            return PositionSupplier { positionMs + if (playing) nowMs() - startMs else 0 }
        }
    }
}
