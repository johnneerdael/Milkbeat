package io.github.aedev.flow.player.audio

import android.app.Application
import androidx.media3.common.Player
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class OutputReportingPlayerTest {
    @Test
    fun `the expanded seek progress receives the held position when playback stops`() {
        val manager = EnhancedMusicPlayerManager
        val engine = OutputTestPlayer()
        val reported = OutputReportingPlayer(engine)
        val savedPlayer = manager.player
        val savedPosition = manager.currentPosition.value
        val savedState = manager.playerState.value
        val field = manager.javaClass.getDeclaredField("player").apply { isAccessible = true }
        try {
            field.set(null, reported)
            manager.currentPositionState.value = 180_000
            manager.javaClass
                .getDeclaredMethod("setupPlayerListener", Player::class.java)
                .apply { isAccessible = true }
                .invoke(manager, reported)
            reported.hold(167_000, retrying = true)
            assertEquals(167_000L, manager.currentPosition.value)
            assertEquals(167_000L, manager.playerState.value.position)
            assertFalse(manager.playerState.value.isPlaying)
            assertTrue(manager.playerState.value.isBuffering)
        } finally {
            field.set(null, savedPlayer)
            manager.currentPositionState.value = savedPosition
            manager.playbackState.value = savedState
        }
    }

    @Test
    fun `confirmed output failure freezes progress and stops reporting playback`() {
        val engine = OutputTestPlayer()
        val reported = OutputReportingPlayer(engine)
        val playing = mutableListOf<Boolean>()
        reported.addListener(
            object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playing += isPlaying
                }
            },
        )
        assertTrue(reported.isPlaying)

        reported.hold(167_000, retrying = true)
        engine.positionMs = 180_000

        assertTrue(engine.isPlaying)
        assertFalse(reported.isPlaying)
        assertEquals(Player.STATE_BUFFERING, reported.playbackState)
        assertEquals(167_000L, reported.currentPosition)
        assertEquals(listOf(false), playing)
        assertEquals("first", reported.currentMediaItem!!.mediaId)
        assertEquals(2, reported.mediaItemCount)

        reported.clearHold()
        assertTrue(reported.isPlaying)
        assertEquals(180_000L, reported.currentPosition)
        assertEquals(listOf(false, true), playing)
    }

    @Test
    fun `exhausted recovery exposes a paused track with working play controls`() {
        val engine = OutputTestPlayer()
        val reported = OutputReportingPlayer(engine)
        engine.pause()
        engine.stop()
        reported.hold(167_000, retrying = false)

        assertEquals(Player.STATE_READY, reported.playbackState)
        assertFalse(reported.playWhenReady)
        assertFalse(reported.isPlaying)
        assertEquals(167_000L, reported.currentPosition)
        assertTrue(reported.isCommandAvailable(Player.COMMAND_PLAY_PAUSE))
        assertEquals(2, reported.mediaItemCount)
    }
}
