package io.github.aedev.flow.data.local

import android.app.ActivityManager
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.player.audio.visualizer.VisualizerMusicCategory
import io.github.aedev.flow.player.audio.visualizer.VisualizerSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.visualizerDataStore: DataStore<Preferences> by safePreferencesDataStore(name = "visualizer")

// A box sold as 2 GB reports about 1.8-1.95 GB after the kernel's reservations.
private const val MIN_DEFAULT_ON_RAM_MB = 1_792L

/** Visualizations start on unless the device has less than 2 GB of memory; the user can switch them either way. */
internal fun visualizerOnByDefault(totalRamMb: Long): Boolean = totalRamMb >= MIN_DEFAULT_ON_RAM_MB

/** Settings of the now-playing visualizer. */
class VisualizerPreferences
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val appContext = context.applicationContext

        val enabledByDefault: Boolean by lazy {
            val memory = ActivityManager.MemoryInfo()
            appContext.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            visualizerOnByDefault(memory.totalMem / (1024 * 1024))
        }

        val enabled: Flow<Boolean> = appContext.visualizerDataStore.data.map { it[ENABLED] ?: enabledByDefault }

        suspend fun setEnabled(enabled: Boolean) {
            appContext.visualizerDataStore.edit { it[ENABLED] = enabled }
        }

        /** Whether the controls bar shows the engine's diagnostics under the audio meter. */
        val diagnostics: Flow<Boolean> = appContext.visualizerDataStore.data.map { it[DIAGNOSTICS] ?: false }

        suspend fun setDiagnostics(enabled: Boolean) {
            appContext.visualizerDataStore.edit { it[DIAGNOSTICS] = enabled }
        }

        /**
         * How far ahead of the player's own idea of the audible moment the visualizer listens, in ms, for
         * audio stacks that report it late (65 ms on an Amlogic AM6). Negative values listen behind it.
         */
        val timingOffsetMs: Flow<Int> = appContext.visualizerDataStore.data.map { it[TIMING_OFFSET_MS] ?: 0 }

        suspend fun setTimingOffsetMs(offsetMs: Int) {
            appContext.visualizerDataStore.edit { it[TIMING_OFFSET_MS] = offsetMs }
        }

        /**
         * Whether a track that has a music video starts on its picture rather than the visualizer. Off by
         * default: most tracks come with a video, and the visualizer is the app's own view.
         */
        val showMusicVideos: Flow<Boolean> = appContext.visualizerDataStore.data.map { it[SHOW_MUSIC_VIDEOS] ?: false }

        suspend fun setShowMusicVideos(show: Boolean) {
            appContext.visualizerDataStore.edit { it[SHOW_MUSIC_VIDEOS] = show }
        }

        /** ProjectM-TV's engine and quality settings; a value never set takes its device default from [defaults]. */
        fun settings(defaults: VisualizerSettings): Flow<VisualizerSettings> =
            appContext.visualizerDataStore.data.map { prefs ->
                VisualizerSettings(
                    autoChange = prefs[AUTO_CHANGE] ?: defaults.autoChange,
                    musicCategory = VisualizerMusicCategory.normalize(prefs[MUSIC_CATEGORY] ?: defaults.musicCategory),
                    presetSeconds = prefs[PRESET_SECONDS] ?: defaults.presetSeconds,
                    beatCuts = prefs[BEAT_CUTS] ?: defaults.beatCuts,
                    blankDetection = prefs[BLANK_DETECTION] ?: defaults.blankDetection,
                    skipSlowPresets = prefs[SKIP_SLOW_PRESETS] ?: defaults.skipSlowPresets,
                    transitionSeconds = prefs[TRANSITION_SECONDS] ?: defaults.transitionSeconds,
                    transitionMode = prefs[TRANSITION_MODE] ?: defaults.transitionMode,
                    frameRateCap = prefs[FRAME_RATE_CAP] ?: defaults.frameRateCap,
                    renderHeight = prefs[RENDER_HEIGHT] ?: defaults.renderHeight,
                    meshLevel = prefs[MESH_LEVEL] ?: defaults.meshLevel,
                    memoryLimit = prefs[MEMORY_LIMIT] ?: defaults.memoryLimit,
                )
            }

        suspend fun setAutoChange(enabled: Boolean) = set(AUTO_CHANGE, enabled)

        suspend fun setMusicCategory(category: String) = set(MUSIC_CATEGORY, VisualizerMusicCategory.normalize(category))

        suspend fun setPresetSeconds(seconds: Int) = set(PRESET_SECONDS, seconds)

        suspend fun setBeatCuts(enabled: Boolean) = set(BEAT_CUTS, enabled)

        suspend fun setBlankDetection(enabled: Boolean) = set(BLANK_DETECTION, enabled)

        suspend fun setSkipSlowPresets(enabled: Boolean) = set(SKIP_SLOW_PRESETS, enabled)

        suspend fun setTransitionSeconds(seconds: Int) = set(TRANSITION_SECONDS, seconds)

        suspend fun setTransitionMode(mode: Int) = set(TRANSITION_MODE, mode)

        suspend fun setFrameRateCap(fps: Int) = set(FRAME_RATE_CAP, fps)

        suspend fun setRenderHeight(height: Int) = set(RENDER_HEIGHT, height)

        suspend fun setMeshLevel(level: Int) = set(MESH_LEVEL, level)

        suspend fun setMemoryLimit(enabled: Boolean) = set(MEMORY_LIMIT, enabled)

        private suspend fun <T> set(
            key: Preferences.Key<T>,
            value: T,
        ) {
            appContext.visualizerDataStore.edit { it[key] = value }
        }

        private companion object {
            val AUTO_CHANGE = booleanPreferencesKey("auto_change")
            val MUSIC_CATEGORY = stringPreferencesKey("music_category")
            val PRESET_SECONDS = intPreferencesKey("preset_seconds")
            val BEAT_CUTS = booleanPreferencesKey("beat_cuts")
            val BLANK_DETECTION = booleanPreferencesKey("blank_detection")
            val SKIP_SLOW_PRESETS = booleanPreferencesKey("skip_slow_presets")
            val TRANSITION_SECONDS = intPreferencesKey("transition_seconds")
            val TRANSITION_MODE = intPreferencesKey("transition_mode")
            val FRAME_RATE_CAP = intPreferencesKey("frame_rate_cap")
            val RENDER_HEIGHT = intPreferencesKey("render_height")
            val MESH_LEVEL = intPreferencesKey("mesh_level")
            val MEMORY_LIMIT = booleanPreferencesKey("memory_limit")
            val TIMING_OFFSET_MS = intPreferencesKey("timing_offset_ms")
            val ENABLED = booleanPreferencesKey("enabled")
            val DIAGNOSTICS = booleanPreferencesKey("diagnostics")
            val SHOW_MUSIC_VIDEOS = booleanPreferencesKey("show_music_videos")
        }
    }
