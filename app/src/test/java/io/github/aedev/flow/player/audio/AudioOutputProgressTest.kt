package io.github.aedev.flow.player.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioOutputProgressTest {
    @Test
    fun `a frozen nonzero head stalls despite an advancing player position`() {
        val progress = AudioOutputProgress()
        progress.sample(1, 8_057_928, 0, 167_000)
        assertFalse(progress.sample(1, 8_057_928, 4_000, 171_000).stalled)

        val result = progress.sample(1, 8_057_928, 5_000, 172_000)

        assertTrue(result.stalled)
        assertEquals(167_000L, result.resumePositionMs)
    }

    @Test
    fun `silent PCM with advancing frames never stalls`() {
        val progress = AudioOutputProgress()
        repeat(60) { second ->
            assertFalse(progress.sample(1, second * 48_000L, second * 1_000L, second * 1_000L).stalled)
        }
    }

    @Test
    fun `new output gets a fresh startup grace period`() {
        val progress = AudioOutputProgress()
        progress.sample(1, 100_000, 0, 2_000)
        progress.sample(1, 100_000, 4_000, 6_000)
        assertFalse(progress.sample(2, 0, 5_000, 2_000).stalled)
        assertFalse(progress.sample(2, 0, 9_000, 6_000).stalled)
        assertTrue(progress.sample(2, 0, 10_000, 7_000).stalled)
    }

    @Test
    fun `unsigned playback head rollover is progress`() {
        val progress = AudioOutputProgress()
        progress.sample(1, 0xffff_fff0L, 0, 80_000)
        val result = progress.sample(1, 32, 1_000, 81_000)
        assertTrue(result.advanced)
        assertFalse(result.stalled)
        assertEquals(80_000L, result.resumePositionMs)
    }

    @Test
    fun `a final partial frame advance cannot authorize an extrapolated seek position`() {
        val progress = AudioOutputProgress()
        progress.sample(1, 8_057_920, 0, 167_000)
        progress.sample(1, 8_057_928, 1_000, 173_000)
        val result = progress.sample(1, 8_057_928, 6_000, 180_000)
        assertTrue(result.stalled)
        assertEquals(167_000L, result.resumePositionMs)
    }

    @Test
    fun `a pause or seek reset cannot inherit an old stall deadline`() {
        val progress = AudioOutputProgress()
        progress.sample(1, 100_000, 0, 2_000)
        progress.reset()
        assertFalse(progress.sample(1, 100_000, 60_000, 120_000).stalled)
        assertTrue(progress.sample(1, 100_000, 65_000, 125_000).stalled)
    }
}
