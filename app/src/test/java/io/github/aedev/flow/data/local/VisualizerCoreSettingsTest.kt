package io.github.aedev.flow.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.audio.visualizer.VisualizerSettings
import org.junit.Test

class VisualizerCoreSettingsTest {
    @Test
    fun `retired fixed resolution and memory toggle do not override automatic settings`() {
        val preferences =
            preferencesOf(
                intPreferencesKey("render_height") to 2160,
                booleanPreferencesKey("memory_limit") to false,
                intPreferencesKey("frame_rate_cap") to 60,
            )
        val settings = readVisualizerSettings(preferences, VisualizerSettings())
        assertThat(settings).isEqualTo(VisualizerSettings(frameRateCap = 60))
        assertThat(settings.clampedNativeTrails).isEqualTo(0)
    }

    @Test
    fun `stored trail gain survives while invalid gains use Standard`() {
        for (level in 0..2) {
            val settings = readVisualizerSettings(preferencesOf(intPreferencesKey("native_trails") to level), VisualizerSettings())
            assertThat(settings.clampedNativeTrails).isEqualTo(level)
        }
        for (level in listOf(-1, 3, Int.MAX_VALUE)) {
            val settings = readVisualizerSettings(preferencesOf(intPreferencesKey("native_trails") to level), VisualizerSettings())
            assertThat(settings.clampedNativeTrails).isEqualTo(0)
        }
    }

    @Test
    fun `troubleshooting switches default on and keep a stored off`() {
        assertThat(readVisualizerSettings(preferencesOf(), VisualizerSettings()).backgroundCompile).isTrue()
        assertThat(readVisualizerSettings(preferencesOf(), VisualizerSettings()).shaderBinaryCache).isTrue()

        val settings =
            readVisualizerSettings(
                preferencesOf(
                    booleanPreferencesKey("background_compile") to false,
                    booleanPreferencesKey("shader_binary_cache") to false,
                ),
                VisualizerSettings(),
            )
        assertThat(settings.backgroundCompile).isFalse()
        assertThat(settings.shaderBinaryCache).isFalse()
    }
}
