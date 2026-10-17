package io.github.aedev.flow.player.audio.visualizer

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.neerdael.projectm.core.PresetFacets
import nl.neerdael.projectm.core.ProjectMCore
import javax.inject.Inject
import javax.inject.Singleton

/** The engine category that plays only the presets handed over through `setPresetPool`. */
internal const val MOOD_CATEGORY = "facets"

/** What the engine should play for a mood selection: a category, and the pool when that category is [MOOD_CATEGORY]. */
internal class MoodPlan(
    val category: String,
    val pool: Array<String>?,
    val exhausted: Boolean,
)

/**
 * A selection plays through the engine's pool category; with none, or none that matches a playable
 * preset, the listener's own category plays and a stored selection is kept for later.
 */
internal fun planMoods(
    facets: PresetFacets?,
    selected: Set<String>,
    skipped: Set<String>,
    baseCategory: String,
): MoodPlan {
    if (facets == null || selected.isEmpty()) return MoodPlan(baseCategory, null, false)
    val pool = facets.select(selected, skipped).presetNames()
    return if (pool.isEmpty()) MoodPlan(baseCategory, null, true) else MoodPlan(MOOD_CATEGORY, pool, false)
}

/** Toggles [id], refusing an option that would leave no preset; null when nothing may change. */
internal fun toggledMoods(
    facets: PresetFacets,
    selected: Set<String>,
    skipped: Set<String>,
    id: String,
): Set<String>? {
    val current = facets.select(selected, skipped)
    if (id in selected) return selected - id
    return if (current.countIfToggled(id) == 0) null else selected + id
}

/**
 * The bundled preset mood table (ProjectM-TV's `preset-attributes`) and the presets the engine has
 * skipped, which a mood pool leaves out. Read off the main thread once, then served from memory.
 */
@Singleton
class VisualizerMoods
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val loadLock = Mutex()

        @Volatile
        private var loaded = false

        @Volatile
        var facets: PresetFacets? = null
            private set

        @Volatile
        var skipped: Set<String> = emptySet()
            private set

        suspend fun load(): PresetFacets? =
            loadLock.withLock {
                withContext(Dispatchers.IO) {
                    if (!loaded) {
                        facets = PresetFacets.load(context.assets)
                        refreshSkipped()
                        loaded = true
                    }
                    facets
                }
            }

        /** Rereads the engine's skip list; the panel calls it on open, so a mix never counts a preset that no longer plays. */
        suspend fun refreshSkipped() =
            withContext(Dispatchers.IO) {
                skipped =
                    ProjectMCore
                        .skipListFile(context)
                        .takeIf { it.exists() }
                        ?.useLines { lines -> lines.filter { it.isNotEmpty() }.toSet() }
                        .orEmpty()
            }

        internal fun plan(settings: VisualizerSettings): MoodPlan = planMoods(facets, settings.moods, skipped, settings.musicCategory)
    }
