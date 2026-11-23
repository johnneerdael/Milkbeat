package io.github.aedev.flow.ui.tv.music

import androidx.media3.common.Player
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.normalizeVisualizerBrightness
import org.junit.Test

class VisualizerScreenOnTest {
    @Test
    fun `unknown brightness preferences use the unmodified visualizer`() {
        assertThat(normalizeVisualizerBrightness(null)).isEqualTo(100)
        assertThat(normalizeVisualizerBrightness(0)).isEqualTo(100)
        assertThat(normalizeVisualizerBrightness(25)).isEqualTo(25)
        assertThat(normalizeVisualizerBrightness(50)).isEqualTo(50)
        assertThat(normalizeVisualizerBrightness(75)).isEqualTo(75)
        assertThat(normalizeVisualizerBrightness(100)).isEqualTo(100)
    }

    @Test
    fun `only active playback and bounded buffering keep the display on`() {
        val policy = VisualizerScreenOn()

        fun check(
            playing: Boolean,
            state: Int,
            intent: Boolean,
            time: Long,
            suppressed: Boolean = false,
        ) = policy.update(
            playing,
            state,
            intent,
            if (suppressed) Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS else Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            time,
        )
        assertThat(check(false, Player.STATE_BUFFERING, true, 0)).isFalse()
        assertThat(check(true, Player.STATE_READY, true, 100)).isTrue()
        assertThat(check(false, Player.STATE_BUFFERING, true, 200)).isTrue()
        assertThat(check(false, Player.STATE_BUFFERING, true, 30_199)).isTrue()
        assertThat(check(false, Player.STATE_BUFFERING, true, 30_200)).isFalse()
        assertThat(check(false, Player.STATE_BUFFERING, false, 30_201)).isFalse()
        assertThat(check(true, Player.STATE_READY, true, 31_000)).isTrue()
        assertThat(check(false, Player.STATE_BUFFERING, true, 31_001, suppressed = true)).isFalse()
        assertThat(check(false, Player.STATE_BUFFERING, true, 31_002)).isFalse()
    }
}
