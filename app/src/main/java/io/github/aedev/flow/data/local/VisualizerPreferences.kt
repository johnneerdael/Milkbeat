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

        val enabled: Flow<Boolean> = appContext.visualizerDataStore.data.map { it[VisualizerKeys.ENABLED] ?: enabledByDefault }

        suspend fun setEnabled(enabled: Boolean) {
            appContext.visualizerDataStore.edit { it[VisualizerKeys.ENABLED] = enabled }
        }

        /** Whether the controls bar shows the engine's diagnostics under the audio meter. */
        val diagnostics: Flow<Boolean> = appContext.visualizerDataStore.data.map { it[VisualizerKeys.DIAGNOSTICS] ?: false }

        suspend fun setDiagnostics(enabled: Boolean) {
            appContext.visualizerDataStore.edit { it[VisualizerKeys.DIAGNOSTICS] = enabled }
        }

        /**
         * How far ahead of the player's own idea of the audible moment the visualizer listens, in ms, for
         * audio stacks that report it late (65 ms on an Amlogic AM6). Negative values listen behind it.
         */
        val timingOffsetMs: Flow<Int> = appContext.visualizerDataStore.data.map { it[VisualizerKeys.TIMING_OFFSET_MS] ?: 0 }

        suspend fun setTimingOffsetMs(offsetMs: Int) {
            appContext.visualizerDataStore.edit { it[VisualizerKeys.TIMING_OFFSET_MS] = offsetMs }
        }

        /**
         * The view now-playing shows, as the player's view button last left it. The visualizer until
         * then; the music-video switch this replaced still decides it for anyone who turned videos on.
         */
        val nowPlayingView: Flow<NowPlayingView> =
            appContext.visualizerDataStore.data.map { prefs ->
                NowPlayingView.fromName(prefs[VisualizerKeys.NOW_PLAYING_VIEW])
                    ?: if (prefs[VisualizerKeys.SHOW_MUSIC_VIDEOS] == true) NowPlayingView.VIDEO else NowPlayingView.VISUALIZER
            }

        suspend fun setNowPlayingView(view: NowPlayingView) {
            appContext.visualizerDataStore.edit { it[VisualizerKeys.NOW_PLAYING_VIEW] = view.name }
        }

        /** ProjectM-TV's engine and quality settings; a value never set takes its device default from [defaults]. */
        fun settings(defaults: VisualizerSettings): Flow<VisualizerSettings> =
            appContext.visualizerDataStore.data.map { prefs ->
                readVisualizerSettings(prefs, defaults)
            }

        suspend fun setAutoChange(enabled: Boolean) = set(VisualizerKeys.AUTO_CHANGE, enabled)

        suspend fun setMusicCategory(category: String) = set(VisualizerKeys.MUSIC_CATEGORY, VisualizerMusicCategory.normalize(category))

        suspend fun setPresetSeconds(seconds: Int) = set(VisualizerKeys.PRESET_SECONDS, seconds)

        suspend fun setBeatCuts(enabled: Boolean) = set(VisualizerKeys.BEAT_CUTS, enabled)

        suspend fun setBlankDetection(enabled: Boolean) = set(VisualizerKeys.BLANK_DETECTION, enabled)

        suspend fun setSkipSlowPresets(enabled: Boolean) = set(VisualizerKeys.SKIP_SLOW_PRESETS, enabled)

        suspend fun setTransitionSeconds(seconds: Int) = set(VisualizerKeys.TRANSITION_SECONDS, seconds)

        suspend fun setTransitionMode(mode: Int) = set(VisualizerKeys.TRANSITION_MODE, mode)

        suspend fun setFrameRateCap(fps: Int) = set(VisualizerKeys.FRAME_RATE_CAP, fps)

        suspend fun setMeshLevel(level: Int) = set(VisualizerKeys.MESH_LEVEL, level)

        suspend fun setNativeTrails(level: Int) = set(VisualizerKeys.NATIVE_TRAILS, level.takeIf { it in 0..2 } ?: 0)

        suspend fun setBackgroundCompile(enabled: Boolean) = set(VisualizerKeys.BACKGROUND_COMPILE, enabled)

        suspend fun setShaderBinaryCache(enabled: Boolean) = set(VisualizerKeys.SHADER_BINARY_CACHE, enabled)

        private suspend fun <T> set(
            key: Preferences.Key<T>,
            value: T,
        ) {
            appContext.visualizerDataStore.edit { it[key] = value }
        }
    }

/** Retired fixed-height and RAM-toggle keys are deliberately ignored by the automatic core. */
internal fun readVisualizerSettings(
    prefs: Preferences,
    defaults: VisualizerSettings,
): VisualizerSettings =
    VisualizerSettings(
        autoChange = prefs[VisualizerKeys.AUTO_CHANGE] ?: defaults.autoChange,
        musicCategory = VisualizerMusicCategory.normalize(prefs[VisualizerKeys.MUSIC_CATEGORY] ?: defaults.musicCategory),
        presetSeconds = prefs[VisualizerKeys.PRESET_SECONDS] ?: defaults.presetSeconds,
        beatCuts = prefs[VisualizerKeys.BEAT_CUTS] ?: defaults.beatCuts,
        blankDetection = prefs[VisualizerKeys.BLANK_DETECTION] ?: defaults.blankDetection,
        skipSlowPresets = prefs[VisualizerKeys.SKIP_SLOW_PRESETS] ?: defaults.skipSlowPresets,
        transitionSeconds = prefs[VisualizerKeys.TRANSITION_SECONDS] ?: defaults.transitionSeconds,
        transitionMode = prefs[VisualizerKeys.TRANSITION_MODE] ?: defaults.transitionMode,
        frameRateCap = prefs[VisualizerKeys.FRAME_RATE_CAP] ?: defaults.frameRateCap,
        meshLevel = prefs[VisualizerKeys.MESH_LEVEL] ?: defaults.meshLevel,
        nativeTrails = prefs[VisualizerKeys.NATIVE_TRAILS] ?: defaults.nativeTrails,
        backgroundCompile = prefs[VisualizerKeys.BACKGROUND_COMPILE] ?: defaults.backgroundCompile,
        shaderBinaryCache = prefs[VisualizerKeys.SHADER_BINARY_CACHE] ?: defaults.shaderBinaryCache,
    )

private object VisualizerKeys {
    val AUTO_CHANGE = booleanPreferencesKey("auto_change")
    val MUSIC_CATEGORY = stringPreferencesKey("music_category")
    val PRESET_SECONDS = intPreferencesKey("preset_seconds")
    val BEAT_CUTS = booleanPreferencesKey("beat_cuts")
    val BLANK_DETECTION = booleanPreferencesKey("blank_detection")
    val SKIP_SLOW_PRESETS = booleanPreferencesKey("skip_slow_presets")
    val TRANSITION_SECONDS = intPreferencesKey("transition_seconds")
    val TRANSITION_MODE = intPreferencesKey("transition_mode")
    val FRAME_RATE_CAP = intPreferencesKey("frame_rate_cap")
    val MESH_LEVEL = intPreferencesKey("mesh_level")
    val NATIVE_TRAILS = intPreferencesKey("native_trails")
    val BACKGROUND_COMPILE = booleanPreferencesKey("background_compile")
    val SHADER_BINARY_CACHE = booleanPreferencesKey("shader_binary_cache")
    val TIMING_OFFSET_MS = intPreferencesKey("timing_offset_ms")
    val ENABLED = booleanPreferencesKey("enabled")
    val DIAGNOSTICS = booleanPreferencesKey("diagnostics")
    val SHOW_MUSIC_VIDEOS = booleanPreferencesKey("show_music_videos")
    val NOW_PLAYING_VIEW = stringPreferencesKey("now_playing_view")
}
