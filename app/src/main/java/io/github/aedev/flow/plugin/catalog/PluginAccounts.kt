package io.github.aedev.flow.plugin.catalog

import android.os.SystemClock
import android.util.Log
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.WebLoginResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whose account each plugin is serving, as the plugin reports it. Pages cached for another account
 * are stale, so the catalog reloads when an entry changes; a call answering that the sign-in expired
 * marks the account expired here too, and the plugin is asked again in the background whether it
 * still signs in, so a refusal that did not end the sign-in heals without the listener.
 */
@Singleton
class PluginAccounts internal constructor(
    private val host: PluginHost,
    private val scope: CoroutineScope,
    private val nowMs: () -> Long,
) {
    @Inject
    constructor(host: PluginHost) : this(
        host,
        CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO),
        SystemClock::elapsedRealtime,
    )

    private val _accounts = MutableStateFlow<Map<String, ProviderAccount>>(emptyMap())
    val accounts: StateFlow<Map<String, ProviderAccount>> = _accounts.asStateFlow()

    private val revalidations = mutableMapOf<String, Job>()
    private val lastRevalidationMs = mutableMapOf<String, Long>()
    private val expiredDuringCheck = mutableSetOf<String>()

    suspend fun refresh(pluginId: String): ProviderAccount {
        val account = ask(pluginId)
        _accounts.update { it + (pluginId to account) }
        return account
    }

    suspend fun complete(
        pluginId: String,
        result: WebLoginResult,
    ): ProviderAccount {
        cancelRevalidation(pluginId)
        val account = host.call(pluginId, PluginOperations.completeSignIn, result)
        _accounts.update { it + (pluginId to account) }
        return account
    }

    suspend fun signOut(pluginId: String) {
        cancelRevalidation(pluginId)
        host.call(pluginId, PluginOperations.signOut, Unit)
        _accounts.update { it + (pluginId to ProviderAccount.Anonymous) }
    }

    /** A call said the plugin's sign-in expired; the plugin is asked again right away whether it still signs in. */
    fun expired(pluginId: String) {
        _accounts.update { it + (pluginId to ProviderAccount.Expired) }
        synchronized(revalidations) {
            if (revalidations[pluginId]?.isActive == true) {
                expiredDuringCheck += pluginId
                return
            }
            revalidations[pluginId] = scope.launch { revalidate(pluginId) }
        }
    }

    private suspend fun revalidate(pluginId: String) {
        val job = currentCoroutineContext()[Job]
        while (true) {
            checkOnce(pluginId)
            synchronized(revalidations) {
                // An expiry reported while this check ran may postdate its answer, so it earns another check.
                if (!expiredDuringCheck.remove(pluginId)) {
                    if (revalidations[pluginId] === job) revalidations.remove(pluginId)
                    return
                }
            }
        }
    }

    private suspend fun checkOnce(pluginId: String) {
        val last = synchronized(revalidations) { lastRevalidationMs[pluginId] }
        if (last != null) delay((last + REVALIDATION_COOLDOWN_MS - nowMs()).coerceAtLeast(0))
        for (attempt in 0..RETRY_BACKOFF_MS.size) {
            synchronized(revalidations) { lastRevalidationMs[pluginId] = nowMs() }
            val account =
                try {
                    ask(pluginId)
                } catch (e: PluginCallException) {
                    val backoffMs = RETRY_BACKOFF_MS.getOrNull(attempt)
                    if (e.error.code !in TRANSIENT || backoffMs == null) return
                    delay(maxOf(backoffMs, e.error.retryAfterMs ?: 0))
                    continue
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Re-checking the $pluginId account failed", e)
                    return
                }
            _accounts.update { if (it[pluginId] == ProviderAccount.Expired) it + (pluginId to account) else it }
            return
        }
    }

    private suspend fun ask(pluginId: String): ProviderAccount =
        try {
            host.call(pluginId, PluginOperations.account, Unit)
        } catch (e: PluginCallException) {
            if (e.error.code == PluginErrorCode.UNSUPPORTED) ProviderAccount.Anonymous else throw e
        }

    private fun cancelRevalidation(pluginId: String) {
        synchronized(revalidations) {
            expiredDuringCheck -= pluginId
            revalidations.remove(pluginId)?.cancel()
        }
    }

    private companion object {
        const val TAG = "PluginAccounts"

        /** A plugin that keeps answering "expired" after confirming the account is asked at most this often. */
        const val REVALIDATION_COOLDOWN_MS = 30_000L
        val RETRY_BACKOFF_MS = listOf(15_000L, 60_000L, 300_000L)
        val TRANSIENT = setOf(PluginErrorCode.NETWORK, PluginErrorCode.TIMEOUT, PluginErrorCode.RATE_LIMITED, PluginErrorCode.UNAVAILABLE)
    }
}
