package io.github.aedev.flow.plugin.runtime

import kotlinx.coroutines.delay
import nl.neerdael.milkbeat.plugin.PluginErrorCode

/**
 * Failures a provider may answer differently in a moment: a throttled request, a dropped connection, a
 * slow answer. UNAVAILABLE is not one: it says this provider cannot serve the item, so another should.
 */
internal val TransientPluginErrors =
    setOf(PluginErrorCode.NETWORK, PluginErrorCode.TIMEOUT, PluginErrorCode.RATE_LIMITED)

internal val TransientRetryBackoffMs = listOf(5_000L, 15_000L, 45_000L)

/**
 * How long to wait before trying again after [error] on the zero-based [attempt], or null when another
 * try cannot help: the failure is not transient, or the [backoffMs] schedule is spent. A provider that
 * asks for a longer pause gets it.
 */
internal fun transientRetryDelayMs(
    error: Throwable,
    attempt: Int,
    backoffMs: List<Long> = TransientRetryBackoffMs,
): Long? {
    val failure = (error as? PluginCallException)?.error ?: return null
    if (failure.code !in TransientPluginErrors) return null
    val waitMs = backoffMs.getOrNull(attempt) ?: return null
    return maxOf(waitMs, failure.retryAfterMs ?: 0)
}

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
            delay(transientRetryDelayMs(e, attempt++, backoffMs) ?: throw e)
            beforeRetry()
        }
    }
}
