package io.github.aedev.flow.player.audio.visualizer

import nl.neerdael.projectm.core.DeviceProfile
import nl.neerdael.projectm.core.ProjectMJNI
import kotlin.math.abs
import kotlin.math.roundToInt

/** ProjectM-TV's settings as the user set them; a value never set takes this device's default. */
data class VisualizerSettings(
    val autoChange: Boolean = true,
    val musicCategory: String = VisualizerMusicCategory.ALL,
    val presetSeconds: Int = DEFAULT_PRESET_SECONDS,
    val beatCuts: Boolean = false,
    val blankDetection: Boolean = true,
    val skipSlowPresets: Boolean = true,
    val transitionSeconds: Int = 7,
    val transitionMode: Int = ProjectMJNI.TRANSITION_AUTO,
    val frameRateCap: Int = 30,
    val renderHeight: Int = AUTO_RENDER_HEIGHT,
    val meshLevel: Int = 2,
    val memoryLimit: Boolean = true,
) {
    val clampedMeshLevel: Int get() = meshLevel.coerceIn(0, DeviceProfile.MESH_SIZES.lastIndex)

    val clampedTransitionSeconds: Int get() = transitionSeconds.coerceIn(0, MAX_TRANSITION_SECONDS)

    companion object {
        const val DEFAULT_PRESET_SECONDS = 30
        const val AUTO_RENDER_HEIGHT = 0
        const val MAX_TRANSITION_SECONDS = 10
        val PRESET_SECONDS = listOf(10, 15, 20, 30, 45, 60, 90)
        val TRANSITION_MODES =
            listOf(ProjectMJNI.TRANSITION_AUTO, ProjectMJNI.TRANSITION_LIGHTWEIGHT, ProjectMJNI.TRANSITION_CLASSIC)

        fun defaultsFor(profile: DeviceProfile) =
            VisualizerSettings(
                skipSlowPresets = profile.defaultSkipSlowPresets(),
                transitionSeconds = profile.defaultTransitionSeconds(),
                frameRateCap = profile.defaultFrameRateCap(),
                meshLevel = profile.defaultMeshLevel(),
            )
    }
}

/** The preset groups the engine sorts its library into, as ProjectM-TV offers them. */
object VisualizerMusicCategory {
    const val ALL = "all"
    val IDS =
        listOf(
            ALL,
            "dance",
            "pop",
            "rock",
            "hip-hop",
            "rnb-soul",
            "jazz",
            "classical",
            "ambient",
            "folk-acoustic",
            "country",
            "reggae",
            "latin",
        )

    fun normalize(id: String?): String = id?.takeIf { it in IDS } ?: ALL

    /**
     * The categories that hold presets. Until the engine has indexed its library every count is 0,
     * and then all are offered: the engine falls back to All by itself when one turns out empty.
     */
    fun available(count: (String) -> Int): List<String> {
        val filled = IDS.filter { it == ALL || count(it) > 0 }
        return if (filled.size > 1) filled else IDS
    }
}

/** Renders on every n-th vsync, n in 1, 2 or 4, whichever rate lands closest to [cap] without dropping below 24 fps. */
fun frameDivisor(
    refreshRate: Float,
    cap: Int,
): Int = listOf(1, 2, 4).filter { it == 1 || refreshRate / it >= MIN_FPS }.minBy { abs(refreshRate / it - cap) }

/** The frame rates the display can render at: its refresh rate divided by 4, 2 or 1, at least 24 fps, ascending. */
fun frameRateOptions(refreshRate: Float): List<Int> =
    listOf(4, 2, 1)
        .map { (refreshRate / it).roundToInt() }
        .filter { it >= MIN_FPS }
        .distinct()
        .ifEmpty { listOf(refreshRate.roundToInt()) }

private const val MIN_FPS = 24f
