package io.github.aedev.flow.player.audio.visualizer

import com.google.common.truth.Truth.assertThat
import nl.neerdael.projectm.core.DeviceProfile
import org.junit.Test

class VisualizerSettingsTest {
    private fun profile(tier: DeviceProfile.Tier): DeviceProfile {
        val constructor = DeviceProfile::class.java.getDeclaredConstructor(DeviceProfile.Tier::class.java, Long::class.javaPrimitiveType)
        constructor.isAccessible = true
        return constructor.newInstance(tier, 4096L)
    }

    @Test
    fun `device defaults follow ProjectM-TV's profile`() {
        val low = VisualizerSettings.defaultsFor(profile(DeviceProfile.Tier.LOW))
        assertThat(low.transitionSeconds).isEqualTo(2)
        assertThat(low.meshLevel).isEqualTo(1)
        assertThat(low.frameRateCap).isEqualTo(30)
        assertThat(low.skipSlowPresets).isTrue()

        val high = VisualizerSettings.defaultsFor(profile(DeviceProfile.Tier.HIGH))
        assertThat(high.transitionSeconds).isEqualTo(7)
        assertThat(high.meshLevel).isEqualTo(3)
        assertThat(high.autoChange).isTrue()
        assertThat(high.presetSeconds).isEqualTo(30)
        assertThat(high.clampedNativeTrails).isEqualTo(0)
        assertThat(high.blankDetection).isTrue()
        assertThat(high.beatCuts).isFalse()
    }

    @Test
    fun `out-of-range stored values are clamped before reaching the engine`() {
        val settings = VisualizerSettings(meshLevel = 9, transitionSeconds = 30)
        assertThat(settings.clampedMeshLevel).isEqualTo(DeviceProfile.MESH_SIZES.lastIndex)
        assertThat(settings.clampedTransitionSeconds).isEqualTo(VisualizerSettings.MAX_TRANSITION_SECONDS)
        assertThat(VisualizerSettings(meshLevel = -1).clampedMeshLevel).isEqualTo(0)
    }

    @Test
    fun `frame rate options divide the refresh rate and stay at 24 fps or above`() {
        assertThat(frameRateOptions(60f)).containsExactly(30, 60).inOrder()
        assertThat(frameRateOptions(120f)).containsExactly(30, 60, 120).inOrder()
        assertThat(frameRateOptions(50f)).containsExactly(25, 50).inOrder()
        assertThat(frameRateOptions(24f)).containsExactly(24)
    }

    @Test
    fun `music categories keep unknown ids out and offer only filled ones once indexed`() {
        assertThat(VisualizerMusicCategory.normalize("chill")).isEqualTo("chill")
        assertThat(VisualizerMusicCategory.normalize("jazz")).isEqualTo(VisualizerMusicCategory.ALL)
        assertThat(VisualizerMusicCategory.normalize("polka")).isEqualTo(VisualizerMusicCategory.ALL)
        assertThat(VisualizerMusicCategory.normalize(null)).isEqualTo(VisualizerMusicCategory.ALL)

        assertThat(VisualizerMusicCategory.available { 0 }).isEqualTo(VisualizerMusicCategory.IDS)
        val counts = mapOf("normal" to 12, "chill" to 3)
        assertThat(VisualizerMusicCategory.available { counts[it] ?: 0 })
            .containsExactly(VisualizerMusicCategory.ALL, "chill", "normal")
            .inOrder()
    }
}
