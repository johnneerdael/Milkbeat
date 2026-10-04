package io.github.aedev.flow.player.factory

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import io.github.aedev.flow.player.config.PlayerConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The Shorts and music profiles are fixed constants rather than user preferences, so nothing else
 * re-checks them: [io.github.aedev.flow.data.local.BufferDurationsTest] only covers the video path,
 * whose durations come from DataStore. These tests are what stops an edit to the constants from
 * reaching a device as the #788 class of failure — `setBufferDurationsMs` throws while the player is
 * being built, which surfaces as a crash on every launch rather than as degraded buffering.
 */
@UnstableApi
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LoadControlFactoryTest {
    @Test
    fun `music profile is accepted by the load control`() {
        LoadControlFactory.forMusic()
    }

    @Test
    fun `music constants satisfy the load control contract before any coercion`() {
        assertContractHolds(
            profile = "music",
            minMs = PlayerConfig.MUSIC_MIN_BUFFER_MS,
            maxMs = PlayerConfig.MUSIC_MAX_BUFFER_MS,
            playbackMs = PlayerConfig.MUSIC_BUFFER_FOR_PLAYBACK_MS,
            rebufferMs = PlayerConfig.MUSIC_BUFFER_FOR_REBUFFER_MS,
        )
    }

    @Test
    fun `music keeps loading a whole song and stops at its byte budget`() {
        val control = LoadControlFactory.forMusic()
        val player = PlayerId("music")
        control.onPrepared(player)
        val item = MediaItem.Builder().setUri("music://spotify:track:fixture").build()
        val timeline = SinglePeriodTimeline(600_000_000L, true, false, false, null, item)

        fun buffered(seconds: Long) =
            LoadControl.Parameters(
                player,
                timeline,
                MediaPeriodId(timeline.getUidOfPeriod(0)),
                0,
                seconds * 1_000_000,
                1f,
                true,
                false,
                C.TIME_UNSET,
                C.TIME_UNSET,
            )

        assertTrue("first note waits for one second only", control.shouldStartPlayback(buffered(1)))
        assertTrue("loading starts below the floor", control.shouldContinueLoading(buffered(1)))
        assertTrue("loading continues past the former 30 s window", control.shouldContinueLoading(buffered(31)))
        assertTrue("a ten minute song is loaded completely", control.shouldContinueLoading(buffered(600)))

        repeat(PlayerConfig.MUSIC_TARGET_BUFFER_BYTES / C.DEFAULT_BUFFER_SEGMENT_SIZE) { control.getAllocator(player).allocate() }
        assertFalse("the byte budget bounds the buffer", control.shouldContinueLoading(buffered(600)))
        assertFalse("above the floor the budget still holds", control.shouldContinueLoading(buffered(31)))
        assertTrue(
            "a picture filling the budget first still leaves the floor loaded",
            control.shouldContinueLoading(buffered(PlayerConfig.MUSIC_MIN_BUFFER_MS / 1_000L - 1)),
        )
        assertEquals(0L, control.getBackBufferDurationUs(player))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `the load control still rejects a profile that inverts min and rebuffer`() {
        // Keeps the checks above from passing vacuously.
        DefaultLoadControl.Builder().setBufferDurationsMs(1_500, 8_000, 250, 5_000)
    }

    /**
     * Asserts the raw constants, not the coerced output of [LoadControlFactory.build] — the coercion
     * is a backstop, and a profile that only survives because of it has drifted from its intent.
     */
    private fun assertContractHolds(
        profile: String,
        minMs: Int,
        maxMs: Int,
        playbackMs: Int,
        rebufferMs: Int,
    ) {
        assertTrue("$profile: max ($maxMs) must be >= min ($minMs)", maxMs >= minMs)
        assertTrue("$profile: min ($minMs) must be >= playback ($playbackMs)", minMs >= playbackMs)
        assertTrue("$profile: min ($minMs) must be >= rebuffer ($rebufferMs)", minMs >= rebufferMs)

        DefaultLoadControl.Builder().setBufferDurationsMs(minMs, maxMs, playbackMs, rebufferMs)
    }
}
