package io.github.aedev.flow.plugin.background

import android.util.Log
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

private const val TAG = "BackgroundBackoff"

/** Background work for [pluginId] waits until [untilMs] (epoch ms). */
data class ProviderPause(
    val pluginId: String,
    val untilMs: Long,
)

/** What the listener is told: background work waits on the provider named [providerName] until [untilMs]. */
data class PausedProvider(
    val providerName: String,
    val untilMs: Long,
)

/** A background job stopped because [pause] is in effect; it is rescheduled for when the pause ends. */
class BackgroundPausedException(
    val pause: ProviderPause,
) : Exception("Background work for ${pause.pluginId} is paused until ${pause.untilMs}")

/**
 * Which providers background jobs leave alone, and until when. A refusal (a provider limiting
 * requests) from a background job, or a refusal page the host sees on any of a provider's
 * requests, pauses background work for that provider; consecutive refusals pause longer, and a
 * background job that completes resets the escalation. See [backgroundPauseMs] for the durations.
 * Foreground and playback requests are never held back by a pause.
 */
@Singleton
class BackgroundProviderBackoff internal constructor(
    private val store: BackoffRecords,
    private val clock: Clock,
    private val random: Random,
) {
    @Inject
    constructor(store: BackoffRecords) : this(store, Clock.systemUTC(), Random.Default)

    /** Every recorded pause by provider, ended ones included; compare [ProviderPause.untilMs] with the time. */
    val pauses: Flow<Map<String, ProviderPause>> =
        store.records.map { records -> records.mapValues { (id, record) -> ProviderPause(id, record.untilMs) } }

    fun now(): Long = clock.millis()

    /** The pause among [pluginIds] that ends last, or null when background work may use all of them. */
    suspend fun activePause(pluginIds: Collection<String>): ProviderPause? {
        val now = now()
        val records = store.records.first()
        return pluginIds
            .mapNotNull { id -> records[id]?.takeIf { it.untilMs > now }?.let { ProviderPause(id, it.untilMs) } }
            .maxByOrNull { it.untilMs }
    }

    /** Throws [BackgroundPausedException] when any of [pluginIds] is paused. */
    suspend fun ensureNotPaused(pluginIds: Collection<String>) {
        activePause(pluginIds)?.let { throw BackgroundPausedException(it) }
    }

    suspend fun ensureNotPaused(pluginId: String) = ensureNotPaused(listOf(pluginId))

    /**
     * Records that [pluginId] refused a request. A refusal that arrives while the provider is already
     * paused (an in-flight request finishing, or a second job) extends the pause to [retryAfterMs] at
     * most and does not count as another strike.
     */
    suspend fun refused(
        pluginId: String,
        retryAfterMs: Long?,
    ): ProviderPause {
        var pause: ProviderPause? = null
        store.update { records ->
            val now = now()
            val previous = records[pluginId]
            val record =
                if (previous != null && previous.untilMs > now) {
                    val asked = now + (retryAfterMs ?: 0L).coerceIn(0L, BACKGROUND_RETRY_AFTER_CEILING_MS)
                    previous.copy(untilMs = maxOf(previous.untilMs, asked))
                } else {
                    val strikes = (previous?.strikes ?: 0) + 1
                    BackoffRecord(now + backgroundPauseMs(retryAfterMs, strikes, random), strikes)
                }
            pause = ProviderPause(pluginId, record.untilMs)
            records + (pluginId to record)
        }
        return checkNotNull(pause).also { Log.w(TAG, "$pluginId refused background work; paused for ${(it.untilMs - now()) / 1000}s") }
    }

    /**
     * A background job using [pluginIds] completed: their escalation starts over. A pause still in
     * effect (set by another job or a foreground refusal meanwhile) is kept.
     */
    suspend fun succeeded(pluginIds: Collection<String>) {
        store.update { records ->
            val now = now()
            records.filterNot { (id, record) -> id in pluginIds && record.untilMs <= now }
        }
    }

    /**
     * Turns [error] into a pause when it is a refusal, or when a refusal page was seen while serving
     * it, and throws [BackgroundPausedException]; returns normally for any other failure.
     */
    suspend fun throwIfRefused(error: PluginCallException) {
        if (error.error.code == PluginErrorCode.RATE_LIMITED) {
            throw BackgroundPausedException(refused(error.pluginId, error.error.retryAfterMs))
        }
        ensureNotPaused(error.pluginId)
    }
}
