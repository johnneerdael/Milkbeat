package io.github.aedev.flow.plugin.catalog

import android.os.SystemClock
import android.util.Log
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.host.MicroGAccounts
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AndroidAccountSignIn
import nl.neerdael.milkbeat.plugin.DeviceCodeBeginRequest
import nl.neerdael.milkbeat.plugin.DeviceCodeChallenge
import nl.neerdael.milkbeat.plugin.DeviceCodePollResult
import nl.neerdael.milkbeat.plugin.DeviceCodeSession
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.WebLoginResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
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
    /** Ends what the host itself holds for a plugin's sign-in, its MicroG account bindings, through a transition. */
    private val revoke: suspend (String, suspend () -> Unit) -> Unit = { _, transition -> transition() },
    /** Whether a host-held sign-in of the plugin was left unfinished by a run that ended, so the plugin signs out. */
    private val orphaned: suspend (String) -> Boolean = { false },
) {
    constructor(host: PluginHost) : this(
        host,
        CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO),
        SystemClock::elapsedRealtime,
    )

    @Inject
    constructor(host: PluginHost, microG: MicroGAccounts) : this(
        host,
        CoroutineScope(SupervisorJob() + PerformanceDispatcher.networkIO),
        SystemClock::elapsedRealtime,
        microG::revoke,
        microG::orphaned,
    )

    private val _accounts = MutableStateFlow<Map<String, ProviderAccount>>(emptyMap())
    val accounts: StateFlow<Map<String, ProviderAccount>> = _accounts.asStateFlow()

    // Account values can return to their old shape after sign-in/sign-out. Native transports
    // retain their provider's epoch, so an old visitor/token cannot survive that ABA transition.
    private val playbackEpochState = MutableStateFlow(0L)
    internal val playbackEpoch: StateFlow<Long> = playbackEpochState.asStateFlow()
    private val providerPlaybackEpochs = ConcurrentHashMap<String, AtomicLong>()

    private fun invalidatePlayback(pluginId: String) {
        providerPlaybackEpochs.getOrPut(pluginId) { AtomicLong() }.incrementAndGet()
        playbackEpochState.update { it + 1 }
    }

    internal fun providerPlaybackContext(pluginId: String): Pair<String, Long> =
        playbackIdentity(_accounts.value[pluginId]) to (providerPlaybackEpochs[pluginId]?.get() ?: 0L)

    internal fun playbackIdentitySnapshot(): Map<String, String> = identitiesOf(_accounts.value)

    internal fun identitiesOf(accounts: Map<String, ProviderAccount>): Map<String, String> =
        accounts.mapValues { playbackIdentity(it.value) }

    private fun playbackIdentity(account: ProviderAccount?): String =
        when (account) {
            is ProviderAccount.SignedIn -> "signed:${account.key}"
            ProviderAccount.Anonymous -> "anonymous"
            ProviderAccount.Expired -> "expired"
            null -> "unknown"
        }

    private val revalidations = mutableMapOf<String, Job>()

    private class AccountCheck(
        val generation: Int,
        val answer: CompletableDeferred<ProviderAccount>,
    )

    private val checks = mutableMapOf<String, AccountCheck>()
    private val generations = mutableMapOf<String, Int>()

    private fun generation(pluginId: String): Int = synchronized(generations) { generations[pluginId] ?: 0 }

    /** A new installation of [pluginId] answers from now on; a check still running asks the one it replaced. */
    fun replaced(pluginId: String) {
        supersedeChecks(pluginId)
        cancelRevalidation(pluginId)
    }

    /** The listener acted on the account; a check still running answers for the account before that. */
    private fun supersedeChecks(pluginId: String) = synchronized(generations) { generations[pluginId] = generation(pluginId) + 1 }

    private val lastRevalidationMs = mutableMapOf<String, Long>()
    private val expiredDuringCheck = mutableSetOf<String>()

    /** Asks [pluginId] for its account; callers asking while a check runs share it rather than repeat it. */
    suspend fun refresh(pluginId: String): ProviderAccount {
        val check = AccountCheck(generation(pluginId), CompletableDeferred())
        // A check begun for an earlier installation or account is not this caller's answer.
        val running =
            synchronized(checks) {
                checks[pluginId]?.takeIf { it.generation == check.generation } ?: run {
                    checks[pluginId] = check
                    null
                }
            }
        if (running != null) {
            return try {
                running.answer.await()
            } catch (e: CancellationException) {
                // The caller running the check was cancelled; one still waiting asks for itself.
                currentCoroutineContext().ensureActive()
                refresh(pluginId)
            }
        }
        try {
            val asked = check.generation
            val answer = ask(pluginId)
            if (generation(pluginId) == asked && playbackIdentity(_accounts.value[pluginId]) != playbackIdentity(answer)) {
                invalidatePlayback(pluginId)
            }
            // A sign-in or sign-out finished while this check ran is newer than its answer.
            var account = answer
            _accounts.update { known ->
                if (generation(pluginId) == asked) {
                    known + (pluginId to answer)
                } else {
                    account = known[pluginId] ?: answer
                    known
                }
            }
            check.answer.complete(account)
            return account
        } catch (e: Throwable) {
            check.answer.completeExceptionally(e)
            throw e
        } finally {
            synchronized(checks) { checks.remove(pluginId, check) }
        }
    }

    /** One sign-in completion of a plugin, for [undoSignIn]; it gets its place once the completion is under way. */
    class Completion {
        @Volatile internal var id: Long? = null
    }

    // Completions, of any method, take their place in one order; each plugin remembers its latest that succeeded.
    // Completions and the undoing of one run one at a time per plugin, so an obsolete sign-in's rollback never ends
    // the account another method completed after it, while one that failed after it does not shield it.
    private val completionOrder = AtomicLong()
    private val latestSucceeded = ConcurrentHashMap<String, Long>()
    private val completionLocks = ConcurrentHashMap<String, Mutex>()

    private suspend fun <T> completing(
        pluginId: String,
        completion: Completion,
        complete: suspend () -> T,
    ): T =
        completionLocks.getOrPut(pluginId) { Mutex() }.withLock {
            val id = completionOrder.incrementAndGet()
            completion.id = id
            complete().also { latestSucceeded[pluginId] = id }
        }

    /** Signs the plugin out after [completion], unless it never began or another completion succeeded after it. */
    suspend fun undoSignIn(
        pluginId: String,
        completion: Completion,
    ) = completionLocks.getOrPut(pluginId) { Mutex() }.withLock {
        val id = completion.id ?: return@withLock
        if ((latestSucceeded[pluginId] ?: 0L) <= id) signOut(pluginId)
    }

    suspend fun complete(
        pluginId: String,
        result: WebLoginResult,
    ): ProviderAccount =
        completing(pluginId, Completion()) {
            supersedeChecks(pluginId)
            cancelRevalidation(pluginId)
            invalidatePlayback(pluginId)
            val account = host.call(pluginId, PluginOperations.completeSignIn, result)
            _accounts.update { it + (pluginId to account) }
            account
        }

    /** Hands the plugin the MicroG account the listener chose and confirmed for its `androidAccount` sign-in. */
    suspend fun completeAccountSignIn(
        pluginId: String,
        signIn: AndroidAccountSignIn,
        completion: Completion = Completion(),
    ): ProviderAccount =
        completing(pluginId, completion) {
            supersedeChecks(pluginId)
            cancelRevalidation(pluginId)
            invalidatePlayback(pluginId)
            val account = host.call(pluginId, PluginOperations.completeAccountSignIn, signIn)
            _accounts.update { it + (pluginId to account) }
            account
        }

    suspend fun beginDeviceSignIn(
        pluginId: String,
        method: String,
    ): DeviceCodeChallenge = host.call(pluginId, PluginOperations.beginSignIn, DeviceCodeBeginRequest(method))

    suspend fun pollDeviceSignIn(
        pluginId: String,
        session: String,
    ): DeviceCodePollResult = host.call(pluginId, PluginOperations.pollSignIn, DeviceCodeSession(session))

    suspend fun cancelDeviceSignIn(
        pluginId: String,
        session: String,
    ) {
        host.call(pluginId, PluginOperations.cancelSignIn, DeviceCodeSession(session))
    }

    /** Cleanup survives navigation destroying its ViewModel; failure leaves only the provider's expiring challenge. */
    fun cancelDeviceSignInAsync(
        pluginId: String,
        session: String,
    ) {
        scope.launch {
            try {
                cancelDeviceSignIn(pluginId, session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    /** Called only after the visible pairing controller has accepted this session's completed result. */
    suspend fun acceptDeviceSignIn(
        pluginId: String,
        session: String,
    ): ProviderAccount.SignedIn =
        completing(pluginId, Completion()) {
            invalidatePlayback(pluginId)
            val account =
                host.call(pluginId, PluginOperations.confirmSignIn, DeviceCodeSession(session)) as? ProviderAccount.SignedIn
                    ?: error("Pairing confirmation did not return a signed-in account")
            require(account.key.isNotBlank()) { "Pairing confirmation returned an empty account identity" }
            supersedeChecks(pluginId)
            cancelRevalidation(pluginId)
            _accounts.update { it + (pluginId to account) }
            account
        }

    // The host's bindings end first and stay ended through the plugin's own sign-out, whatever it does.
    suspend fun signOut(pluginId: String) =
        revoke(pluginId) {
            invalidatePlayback(pluginId)
            supersedeChecks(pluginId)
            cancelRevalidation(pluginId)
            host.call(pluginId, PluginOperations.signOut, Unit)
            _accounts.update { it + (pluginId to ProviderAccount.Anonymous) }
        }

    /** Runs [removal] with the plugin's account bindings ended first, so no later plugin under its id inherits one. */
    suspend fun removing(
        pluginId: String,
        removal: suspend () -> Unit,
    ) = revoke(pluginId, removal)

    /** A call said the plugin's sign-in expired; the plugin is asked again right away whether it still signs in. */
    fun expired(pluginId: String) {
        invalidatePlayback(pluginId)
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
            val asked = generation(pluginId)
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
            if (generation(pluginId) == asked && _accounts.value[pluginId] == ProviderAccount.Expired &&
                account != ProviderAccount.Expired
            ) {
                invalidatePlayback(pluginId)
            }
            _accounts.update {
                if (it[pluginId] == ProviderAccount.Expired &&
                    generation(pluginId) == asked
                ) {
                    it + (pluginId to account)
                } else {
                    it
                }
            }
            return
        }
    }

    private suspend fun ask(pluginId: String): ProviderAccount {
        // The plugin may still report the account an unfinished host sign-in gave it; it cannot get a token for it.
        if (orphaned(pluginId)) {
            signOut(pluginId)
            return ProviderAccount.Anonymous
        }
        return try {
            host.call(pluginId, PluginOperations.account, Unit)
        } catch (e: PluginCallException) {
            if (e.error.code == PluginErrorCode.UNSUPPORTED) ProviderAccount.Anonymous else throw e
        }
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
