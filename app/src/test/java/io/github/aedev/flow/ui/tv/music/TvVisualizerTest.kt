package io.github.aedev.flow.ui.tv.music

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.audio.visualizer.frameDivisor
import nl.neerdael.projectm.core.DeviceProfile
import org.junit.Test

class TvVisualizerTest {
    @Test
    fun `right steps forward, left steps back, other keys are not preset steps`() {
        assertThat(presetStepFor(KeyEvent.KEYCODE_DPAD_RIGHT)).isTrue()
        assertThat(presetStepFor(KeyEvent.KEYCODE_DPAD_LEFT)).isFalse()
        assertThat(presetStepFor(KeyEvent.KEYCODE_DPAD_UP)).isNull()
        assertThat(presetStepFor(KeyEvent.KEYCODE_DPAD_CENTER)).isNull()
    }

    @Test
    fun `the frame divisor lands closest to the cap without going below 24 fps`() {
        assertThat(frameDivisor(refreshRate = 60f, cap = 60)).isEqualTo(1)
        assertThat(frameDivisor(refreshRate = 60f, cap = 30)).isEqualTo(2)
        assertThat(frameDivisor(refreshRate = 120f, cap = 60)).isEqualTo(2)
        assertThat(frameDivisor(refreshRate = 120f, cap = 30)).isEqualTo(4)
        assertThat(frameDivisor(refreshRate = 50f, cap = 20)).isEqualTo(2)
    }

    @Test
    fun `embedded upstream defaults target 30 fps and skip unwatchable presets on every tier`() {
        val constructor = DeviceProfile::class.java.getDeclaredConstructor(DeviceProfile.Tier::class.java, Long::class.javaPrimitiveType)
        constructor.isAccessible = true
        DeviceProfile.Tier.entries.forEach { tier ->
            val profile = constructor.newInstance(tier, 4096L)
            assertThat(profile.defaultFrameRateCap()).isEqualTo(30)
            assertThat(profile.defaultSkipSlowPresets()).isTrue()
            assertThat(frameDivisor(60f, profile.defaultFrameRateCap())).isEqualTo(2)
            assertThat(frameDivisor(120f, profile.defaultFrameRateCap())).isEqualTo(4)
        }
    }

    @Test
    fun `the audio state follows ProjectM-TV's meter labels`() {
        assertThat(visualizerAudioState(0f)).isEqualTo(VisualizerAudioState.NO_SOUND)
        assertThat(visualizerAudioState(0.01f)).isEqualTo(VisualizerAudioState.VERY_QUIET)
        assertThat(visualizerAudioState(0.3f)).isEqualTo(VisualizerAudioState.LISTENING)
    }

    @Test
    fun `the meter rises at once and falls back gently`() {
        val loud = meterFill(level = 0.25f, shown = 0f)
        assertThat(loud).isWithin(1e-4f).of(0.8f)
        assertThat(meterFill(level = 0f, shown = loud)).isWithin(1e-4f).of(0.68f)
        assertThat(meterFill(level = 1f, shown = 0f)).isEqualTo(1f)
    }

    @Test
    fun `preset names drop their folder and extension`() {
        assertThat(
            presetDisplayName("presets/Geiss/Serge + martin - crystal palace000.milk"),
        ).isEqualTo("Serge + martin - crystal palace000")
        assertThat(presetDisplayName("")).isEmpty()
    }
}
