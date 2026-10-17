package io.github.aedev.flow.player.audio.visualizer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.VisualizerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.withContext
import nl.neerdael.projectm.core.DeviceProfile
import nl.neerdael.projectm.core.ProjectMCore
import nl.neerdael.projectm.core.ProjectMJNI
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The embedded projectM engine: whether this device can run it, and its one-time start. The engine
 * is process-wide native state, so it starts on the first visualizer shown and stays up; its
 * compiled shaders then survive closing and reopening now-playing.
 */
@Singleton
class VisualizerEngine
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        preferences: VisualizerPreferences,
        val exitDiagnostics: VisualizerExitDiagnostics,
        val moods: VisualizerMoods,
    ) {
        @Volatile
        private var started = false

        /** The native library ships for ARM only, and projectM needs OpenGL ES 3. */
        val isSupported: Boolean by lazy {
            Build.SUPPORTED_ABIS.any { it in NATIVE_ABIS } &&
                context.getSystemService(ActivityManager::class.java).deviceConfigurationInfo.reqGlEsVersion >= GLES_3
        }

        /** Whether now-playing shows the visualizer: the setting, on a device that can run it. */
        val active: Flow<Boolean> = preferences.enabled.map { it && isSupported }

        /** The auto-resolution height reached last time, so reopening starts there instead of ramping again. */
        var lastAutoHeight = 0

        /** The on-screen visualizer's latest once-a-second frame-rate sample, for the diagnostics line. */
        @Volatile
        var renderStats: VisualizerRenderStats? = null

        val profile: DeviceProfile by lazy { DeviceProfile.detect(context) }

        /** ProjectM-TV's defaults for this device, for every setting the user has not changed. */
        val defaults: VisualizerSettings by lazy { VisualizerSettings.defaultsFor(profile) }

        /** The engine starts only once these are read, so its trail file is open before it first renders. */
        val settings: Flow<VisualizerSettings> by lazy {
            preferences.settings(defaults).onStart {
                if (isSupported) {
                    exitDiagnostics.open()
                    moods.load()
                }
            }
        }

        /** The GPU as its driver names it, once a visualizer has rendered in this process; empty before. */
        @Volatile
        var gpu: String = ""

        private var applied: VisualizerSettings? = null

        @Volatile
        private var replanMoods = false

        fun start(settings: VisualizerSettings) {
            if (!started) {
                started = true
                ProjectMCore.init(context)
            }
            apply(settings)
        }

        /** Hands projectM the settings it keeps itself; only those that changed, as a new mesh or category costs a reload. */
        fun apply(settings: VisualizerSettings) {
            val last = applied
            if (last?.clampedMeshLevel != settings.clampedMeshLevel) {
                val mesh = DeviceProfile.MESH_SIZES[settings.clampedMeshLevel]
                ProjectMJNI.setMeshSize(mesh[0], mesh[1])
            }
            if (last?.autoChange != settings.autoChange) ProjectMJNI.setAutoChange(settings.autoChange)
            if (last?.musicCategory != settings.musicCategory || last.moods != settings.moods) {
                val plan = moods.plan(settings)
                plan.pool?.let(ProjectMJNI::setPresetPool)
                ProjectMJNI.setMusicCategory(plan.category)
            }
            if (last?.beatCuts != settings.beatCuts) ProjectMJNI.setBeatCuts(settings.beatCuts)
            if (last?.presetSeconds != settings.presetSeconds) ProjectMJNI.setPresetDuration(settings.presetSeconds)
            if (last?.blankDetection != settings.blankDetection) ProjectMJNI.setBlankDetection(settings.blankDetection)
            if (last == null) {
                ProjectMJNI.setBackgroundCompile(true)
                ProjectMJNI.setShaderBinaryCache(true)
            }
            if (last?.transitionMode != settings.transitionMode) {
                ProjectMJNI.setTransitionMode(settings.transitionMode, profile.lowerBlendResolutionByDefault())
            }
            applied = settings
        }

        fun categoryPresetCount(category: String): Int = if (started) ProjectMJNI.getCategoryPresetCount(category) else 0

        /** The music categories that hold presets; all of them until the engine has indexed its library. */
        fun musicCategories(): List<String> =
            if (started) VisualizerMusicCategory.available(ProjectMJNI::getCategoryPresetCount) else VisualizerMusicCategory.IDS

        /**
         * Presets the engine stopped showing because they stayed black or ran too slowly. Before the
         * engine runs in this process, its saved list holds them.
         */
        suspend fun skippedPresets(): Int =
            withContext(Dispatchers.IO) {
                if (started) {
                    ProjectMJNI.getSkippedCount()
                } else {
                    skipList().takeIf { it.exists() }?.useLines { lines -> lines.filter { it.isNotEmpty() }.toSet().size } ?: 0
                }
            }

        suspend fun resetSkippedPresets() {
            withContext(Dispatchers.IO) {
                if (started) {
                    ProjectMJNI.resetSkippedPresets()
                } else {
                    skipList().delete()
                    File(skipList().path + BLANK_STRIKES_SUFFIX).delete()
                }
            }
            moods.refreshSkipped()
            replanMoods = true
        }

        private fun skipList(): File = ProjectMCore.skipListFile(context)

        private companion object {
            val NATIVE_ABIS = setOf("arm64-v8a", "armeabi-v7a")
            const val GLES_3 = 0x30000

            // The engine counts a black preset's strikes next to its skip list.
            const val BLANK_STRIKES_SUFFIX = ".blank"
        }
    }

/** How the visualizer on screen is rendering: frames per second against its target, and at what size. */
data class VisualizerRenderStats(
    val fps: Float,
    val targetFps: Int,
    val width: Int,
    val height: Int,
    val autoResolution: Boolean,
)
