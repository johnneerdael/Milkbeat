package io.github.aedev.flow.plugin.runtime

import kotlinx.coroutines.delay
import nl.neerdael.milkbeat.plugin.PluginErrorCode

/**
 * Failures a provider may answer differently in a moment: a throttled request, a dropped connection, a
 * slow answer. UNAVAILABLE is not one: it says this provider cannot serve the item, so another should.
 */
internal val TransientPluginErrors =
    setOf(PluginErrorCode.NETWORK, PluginErrorCode.TIMEOUT, PluginErrorCode.RATE_LIMITED)

/**
 * What a background job may retry in place. A refusal is not among them: sending the same search
 * again seconds later is what keeps a provider's IP flag up, so background jobs pause the provider
 * and stop instead (see `BackgroundProviderBackoff`).
 */
internal val BackgroundTransientPluginErrors = TransientPluginErrors - PluginErrorCode.RATE_LIMITED

internal val TransientRetryBackoffMs = listOf(5_000L, 15_000L, 45_000L)

/**
 * [step] again after each [retryable] failure, waiting [backoffMs] in turn or longer when the provider
 * asks to, so one refused request costs a pause rather than the whole run. The last failure, or any
 * other, is thrown. [beforeRetry] runs after each wait, to stop a run whose context changed meanwhile.
 */
internal suspend fun <T> retryingTransient(
    backoffMs: List<Long> = TransientRetryBackoffMs,
    retryable: Set<PluginErrorCode> = TransientPluginErrors,
    beforeRetry: suspend () -> Unit = {},
    step: suspend () -> T,
): T {
    var attempt = 0
    while (true) {
        try {
            return step()
        } catch (e: PluginCallException) {
            val waitMs = backoffMs.getOrNull(attempt++)
            if (e.error.code !in retryable || waitMs == null) throw e
            delay(maxOf(waitMs, e.error.retryAfterMs ?: 0))
            beforeRetry()
        }
    }
}
