package io.github.aedev.flow.plugin.background

import android.os.SystemClock
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * Spaces the background searches that reach a provider (see [BACKGROUND_LOOKUP_SPACING_MS]).
 * Each request [spend]s spacing proportional to the tracks it searched; the next background
 * request for that provider [awaitTurn]s until the spacing has passed. Answers from the match
 * cache spend nothing, and foreground or playback matching never calls either.
 */
@Singleton
class BackgroundMatchPacer internal constructor(
    private val now: () -> Long,
    private val spacingMs: (requests: Int) -> Long,
) {
    @Inject
    constructor() : this(SystemClock::elapsedRealtime, { backgroundLookupSpacingMs(it, Random.Default) })

    private val nextTurn = mutableMapOf<String, Long>()

    /** Suspends until background work may send [pluginId] its next search. */
    suspend fun awaitTurn(pluginId: String) {
        val wait = synchronized(nextTurn) { (nextTurn[pluginId] ?: 0L) - now() }
        if (wait > 0) delay(wait)
    }

    /** A background request is about to search [pluginId] for [requests] tracks. */
    fun spend(
        pluginId: String,
        requests: Int,
    ) {
        if (requests <= 0) return
        synchronized(nextTurn) {
            nextTurn[pluginId] = maxOf(nextTurn[pluginId] ?: 0L, now()) + spacingMs(requests)
        }
    }
}
