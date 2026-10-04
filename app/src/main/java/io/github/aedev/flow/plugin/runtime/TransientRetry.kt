package io.github.aedev.flow.plugin.runtime

import kotlinx.coroutines.delay
import nl.neerdael.milkbeat.plugin.PluginErrorCode

/** Failures a provider may answer differently in a moment: a refused or throttled request, a dropped connection. */
internal val TransientPluginErrors =
    setOf(PluginErrorCode.NETWORK, PluginErrorCode.TIMEOUT, PluginErrorCode.RATE_LIMITED, PluginErrorCode.UNAVAILABLE)

internal val TransientRetryBackoffMs = listOf(5_000L, 15_000L, 45_000L)

/**
 * [step] again after each transient failure, waiting [backoffMs] in turn or longer when the provider
 * asks to, so one refused request costs a pause rather than the whole run. The last failure, or any
 * other, is thrown. [beforeRetry] runs after each wait, to stop a run whose context changed meanwhile.
 */
internal suspend fun <T> retryingTransient(
    backoffMs: List<Long> = TransientRetryBackoffMs,
    beforeRetry: suspend () -> Unit = {},
    step: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return step()
        } catch (e: PluginCallException) {
            val waitMs = backoffMs.getOrNull(attempt++)
            if (e.error.code !in TransientPluginErrors || waitMs == null) throw e
            delay(maxOf(waitMs, e.error.retryAfterMs ?: 0))
            beforeRetry()
        }
    }
}
