package io.github.aedev.flow.plugin.host

import android.accounts.AuthenticatorException
import android.accounts.OperationCanceledException
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.plugin.AccountIdentity
import nl.neerdael.milkbeat.plugin.AccountToken
import nl.neerdael.milkbeat.plugin.AccountTokenInvalidation
import nl.neerdael.milkbeat.plugin.AccountTokenRequest
import nl.neerdael.milkbeat.plugin.AndroidAccountMethod
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import java.io.File
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// No count equals it, so whatever dates from a running revocation counts as revoked.
private const val REVOKING = -1L

private const val PENDING_SUFFIX = "#pending"

private val Context.pluginAndroidAccounts: DataStore<Preferences> by safePreferencesDataStore(name = "plugin_android_accounts")

/**
 * The MicroG-RE-TV account each plugin's `androidAccount` sign-in is bound to, and the tokens it asks
 * for. Milkbeat keeps only the account name, the signer of the plugin it was chosen for and the
 * identities confirmed; MicroG
 * keeps the grants and caches the tokens. A plugin never names the account itself, so it reaches only
 * the one the listener picked for it, as only the identities confirmed then, and a plugin from another
 * signer under the same id reaches none. Sign-out and removal forget the binding.
 */
@Singleton
class MicroGAccounts internal constructor(
    private val gateway: MicroGAccountGateway,
    private val store: DataStore<Preferences>,
    private val io: CoroutineDispatcher,
    private val tombstones: RevocationTombstones = MemoryRevocationTombstones(),
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        gateway: MicroGAccountGateway,
    ) : this(
        gateway,
        context.pluginAndroidAccounts,
        Dispatchers.IO,
        FileRevocationTombstones(File(context.noBackupFilesDir, "microg_revocations")),
    )

    fun supported(): Boolean = gateway.serves()

    fun installed(): Boolean = gateway.installed()

    fun outdated(): Boolean = gateway.outdated()

    private val attempts = ConcurrentHashMap<String, Mutex>()

    /**
     * Runs one sign-in attempt for a plugin at a time, whatever its method: a failed attempt signs the whole plugin
     * out, which must never undo another method's attempt that has just completed.
     */
    suspend fun <T> signingIn(
        pluginId: String,
        since: Long = revocationMark(pluginId),
        attempt: suspend () -> T,
    ): T =
        attempts.getOrPut(pluginId) { Mutex() }.withLock {
            withContext(Attempt(pluginId, since)) { attempt() }
        }

    /**
     * Marks when a sign-in began, for [signingIn]: a sign-out or removal after it fails the attempt, and so does one
     * that was still running when the mark was taken.
     */
    fun revocationMark(pluginId: String): Long {
        val now = revocations[pluginId] ?: Revocations()
        return if (now.running > 0 || now.undeleted) REVOKING else now.count
    }

    // Per plugin, how often its sign-in was revoked (sign-out, removal), how many revocations still run and whether
    // a failed deletion left bindings behind, kept as one value so all change and are read together. An attempt notes the count it began with, and its bind and
    // confirm fail once a revocation came since or while one runs: the attempt's own rollback signs the plugin out,
    // so revocation cannot simply wait for the attempt's lock.
    private data class Revocations(
        val count: Long = 0L,
        val running: Int = 0,
        val undeleted: Boolean = false,
    )

    private val revocations = ConcurrentHashMap<String, Revocations>()

    // A failed revocation an earlier run left behind counts from the start, loaded once per plugin and run before any
    // binding, confirmation or token is judged.
    private suspend fun load(pluginId: String) {
        if (revocations.containsKey(pluginId)) return
        val undeleted = withContext(io) { runCatching { tombstones.contains(pluginId) }.getOrDefault(false) }
        revocations.putIfAbsent(pluginId, Revocations(undeleted = undeleted))
    }

    private fun revoked(
        pluginId: String,
        since: Long,
    ): Boolean {
        val now = revocations[pluginId] ?: Revocations()
        return now.count != since || now.running > 0 || now.undeleted
    }

    private class Attempt(
        val pluginId: String,
        val revocation: Long,
    ) : AbstractCoroutineContextElement(Attempt) {
        companion object Key : CoroutineContext.Key<Attempt>
    }

    /** Fails a sign-in attempt for [pluginId] that a sign-out or removal overtook. */
    private suspend fun checkNotRevoked(pluginId: String) {
        val attempt = currentCoroutineContext()[Attempt] ?: return
        check(attempt.pluginId != pluginId || !revoked(pluginId, attempt.revocation)) {
            "The plugin was signed out or removed during sign-in"
        }
    }

    fun chooseAccountIntent(): Intent = gateway.chooseAccountIntent()

    /** The sign-in screen's request for [identity], which may answer with MicroG's prompt. */
    suspend fun request(
        accountName: String,
        identity: AccountIdentity,
    ): MicroGTokenResult = withContext(io) { gateway.token(accountName, identity) }

    // The nonce of each binding this run made that the plugin has not completed yet. A record carries the
    // nonce it was written with, so one a process that died left behind never matches this run's.
    private val provisional = ConcurrentHashMap<String, String>()

    /**
     * Binds [method] of [plugin] to [accountName], for the identities the listener has just confirmed. The
     * binding is provisional until [confirm]: it serves tokens in this run only, so the plugin can check the
     * account while it completes the sign-in, and one a dead process left behind serves none.
     */
    suspend fun bind(
        plugin: InstalledPlugin,
        method: AndroidAccountMethod,
        accountName: String,
    ): String {
        load(plugin.id)
        val nonce = UUID.randomUUID().toString()
        // Recorded before the write: until the write lands the stored record carries an older nonce, which fails.
        checkNotRevoked(plugin.id)
        provisional[key(plugin.id, method.id).name] = nonce
        try {
            store.edit {
                it[key(plugin.id, method.id)] = accountName
                it[signerKey(plugin.id, method.id)] = plugin.signerFingerprint
                it[identitiesKey(plugin.id, method.id)] = method.identities.joinToString(",") { identity -> identity.name }
                it[pendingKey(plugin.id, method.id)] = nonce
                it[nonceKey(plugin.id, method.id)] = nonce
            }
            checkNotRevoked(plugin.id)
        } catch (e: Throwable) {
            // The write may have landed before a cancellation reached us; the caller never learns the nonce.
            withContext(NonCancellable) { forget(plugin.id, method.id, nonce) }
            throw e
        }
        return nonce
    }

    /** The plugin completed the sign-in that wrote [nonce]: that binding lasts until sign-out or removal. */
    suspend fun confirm(
        pluginId: String,
        methodId: String,
        nonce: String,
    ) {
        load(pluginId)
        val attempt = currentCoroutineContext()[Attempt]?.takeIf { it.pluginId == pluginId }
        try {
            store.edit {
                check(it[pendingKey(pluginId, methodId)] == nonce) { "The binding was replaced or ended meanwhile" }
                check(
                    attempt == null || !revoked(pluginId, attempt.revocation),
                ) { "The plugin was signed out or removed during sign-in" }
                it.remove(pendingKey(pluginId, methodId))
            }
            // A sign-out or removal that began while the write ran is caught here and undone like any failure.
            check(attempt == null || !revoked(pluginId, attempt.revocation)) { "The plugin was signed out or removed during sign-in" }
        } catch (e: Throwable) {
            // The write may have landed before a cancellation reached us. A confirmation the caller never learned of
            // is undone: still this run's nonce, no longer pending, so nothing newer replaced or revoked it.
            withContext(NonCancellable) {
                store.edit {
                    if (it[pendingKey(pluginId, methodId)] == null && it[nonceKey(pluginId, methodId)] == nonce) {
                        it.removeBinding(pluginId, methodId)
                    }
                }
            }
            throw e
        } finally {
            provisional.remove(key(pluginId, methodId).name, nonce)
        }
    }

    /** Forgets the binding the sign-in that wrote [nonce] made, pending or confirmed, and no other. */
    suspend fun forget(
        pluginId: String,
        methodId: String,
        nonce: String,
    ) {
        // Withdrawn before the write, so no token request serves the abandoned binding while it is deleted.
        provisional.remove(key(pluginId, methodId).name, nonce)
        store.edit { if (it[nonceKey(pluginId, methodId)] == nonce) it.removeBinding(pluginId, methodId) }
    }

    private suspend fun forget(
        pluginId: String,
        methodId: String,
    ) {
        provisional.remove(key(pluginId, methodId).name)
        store.edit { it.removeBinding(pluginId, methodId) }
    }

    private fun MutablePreferences.removeBinding(
        pluginId: String,
        methodId: String,
    ) {
        remove(key(pluginId, methodId))
        remove(signerKey(pluginId, methodId))
        remove(identitiesKey(pluginId, methodId))
        remove(pendingKey(pluginId, methodId))
        remove(nonceKey(pluginId, methodId))
    }

    /**
     * Ends every binding of [pluginId] and runs [transition], the plugin's own sign-out or its removal, with the
     * revocation in force throughout: until it is done no sign-in attempt binds or confirms and no token is handed out.
     */
    suspend fun revoke(
        pluginId: String,
        transition: suspend () -> Unit = {},
    ) {
        // Counted and marked running in one step, before the deletion: whoever sees the new count also sees it run.
        revocations.compute(pluginId) { _, now -> (now ?: Revocations()).let { it.copy(count = it.count + 1, running = it.running + 1) } }
        try {
            // The listener's sign-out or removal completes, transition included, even if whoever started it goes
            // away meanwhile; plugin calls are bounded by the runtime's call timeout.
            withContext(NonCancellable) {
                provisional.keys.removeIf { it.startsWith(prefix(pluginId)) }
                val deletion =
                    try {
                        store.edit { preferences ->
                            preferences
                                .asMap()
                                .keys
                                .filter { it.name.startsWith(prefix(pluginId)) }
                                .forEach { preferences.remove(it) }
                        }
                        null
                    } catch (e: Exception) {
                        e
                    }
                // Bindings the store failed to delete stay revoked, in this run and on disk, until a later revocation
                // deletes them, and the plugin still signs out, so neither outlives the listener's request.
                revocations.computeIfPresent(pluginId) { _, now -> now.copy(undeleted = deletion != null) }
                if (deletion == null) expired -= pluginId
                withContext(io) {
                    runCatching { if (deletion != null) tombstones.add(pluginId) else tombstones.remove(pluginId) }
                        .onFailure { deletion?.addSuppressed(it) }
                }
                try {
                    transition()
                } catch (e: Throwable) {
                    deletion?.let(e::addSuppressed)
                    throw e
                }
                deletion?.let { throw it }
            }
        } finally {
            revocations.computeIfPresent(pluginId) { _, now -> now.copy(running = now.running - 1) }
        }
    }

    /**
     * Whether [pluginId] has a binding a sign-in left unfinished that no attempt of this run is completing, as one in a
     * run that died: its plugin may believe in an account it cannot get a token for, and is signed out.
     */
    suspend fun orphaned(pluginId: String): Boolean {
        load(pluginId)
        // Bindings a revocation failed to delete are retried by the sign-out this answer leads to, and a binding that
        // expired (an identity an update added, a withdrawn grant, a removed account) needs a new sign-in.
        if (revocations[pluginId]?.undeleted == true || pluginId in expired) return true
        return store.data.first().asMap().any { (key, value) ->
            key.name.startsWith(prefix(pluginId)) &&
                key.name.endsWith(PENDING_SUFFIX) &&
                provisional[key.name.removeSuffix(PENDING_SUFFIX)] != value
        }
    }

    // Plugins whose binding answered SIGN_IN_EXPIRED in this run: the plugin may still report its account, so it
    // counts as orphaned until a sign-out clears the binding and the listener signs in again.
    private val expired = ConcurrentHashMap.newKeySet<String>()

    internal suspend fun token(
        plugin: InstalledPlugin,
        request: AccountTokenRequest,
    ): AccountToken =
        try {
            issue(plugin, request)
        } catch (e: HostCallException) {
            if (e.code == PluginErrorCode.SIGN_IN_EXPIRED) expired += plugin.id
            throw e
        }

    private suspend fun issue(
        plugin: InstalledPlugin,
        request: AccountTokenRequest,
    ): AccountToken {
        val method = method(plugin, request.method)
        if (request.identity !in method.identities) {
            throw HostCallException(PluginErrorCode.UNSUPPORTED, "${method.id} does not declare ${request.identity}")
        }
        load(plugin.id)
        val since = revocationMark(plugin.id)
        val account = boundAccount(plugin, method, request.identity)
        val token =
            withContext(io) {
                authenticator {
                    if (!gateway.hasAccount(account)) {
                        throw HostCallException(PluginErrorCode.SIGN_IN_EXPIRED, "The chosen account is no longer on this device")
                    }
                    when (val result = gateway.token(account, request.identity)) {
                        is MicroGTokenResult.Granted -> result.token

                        is MicroGTokenResult.NeedsConsent -> throw HostCallException(
                            PluginErrorCode.SIGN_IN_EXPIRED,
                            "MicroG asks to confirm ${request.identity} again",
                        )
                    }
                }
            }
        // A sign-out or removal that began or was still deleting while MicroG answered ends the binding: its token
        // is not handed out.
        if (revoked(plugin.id, since) || runCatching { boundAccount(plugin, method, request.identity) }.getOrNull() != account) {
            throw HostCallException(PluginErrorCode.SIGN_IN_REQUIRED, "The sign-in for ${method.id} ended")
        }
        return AccountToken(token)
    }

    /** The account [method] of [plugin] is bound to for [identity], or why it may not have a token. */
    private suspend fun boundAccount(
        plugin: InstalledPlugin,
        method: AndroidAccountMethod,
        identity: AccountIdentity,
    ): String {
        val stored = store.data.first()
        val account = stored[key(plugin.id, method.id)]
        if (account == null || stored[signerKey(plugin.id, method.id)] != plugin.signerFingerprint) {
            if (account != null) forget(plugin.id, method.id)
            throw HostCallException(PluginErrorCode.SIGN_IN_REQUIRED, "No account was chosen for ${method.id}")
        }
        // A sign-in that never finished, in this run or one that died, ends the plugin's sign-in rather than
        // serving it; the record stays, since a sign-in running now may be replacing it.
        val pending = stored[pendingKey(plugin.id, method.id)]
        if (pending != null && provisional[key(plugin.id, method.id).name] != pending) {
            throw HostCallException(PluginErrorCode.SIGN_IN_EXPIRED, "The sign-in for ${method.id} did not finish")
        }
        // An identity an update added was never confirmed for this plugin: it signs in again for it.
        val approved = stored[identitiesKey(plugin.id, method.id)].orEmpty().split(',')
        if (identity.name !in approved) {
            throw HostCallException(PluginErrorCode.SIGN_IN_EXPIRED, "$identity was not confirmed for ${method.id}")
        }
        return account
    }

    internal suspend fun invalidate(
        plugin: InstalledPlugin,
        request: AccountTokenInvalidation,
    ) {
        method(plugin, request.method)
        withContext(io) { gateway.invalidate(request.token) }
    }

    private fun method(
        plugin: InstalledPlugin,
        id: String,
    ): AndroidAccountMethod =
        plugin.manifest.signIn
            .filterIsInstance<AndroidAccountMethod>()
            .firstOrNull { it.id == id }
            ?: throw HostCallException(PluginErrorCode.UNSUPPORTED, "${plugin.id} has no account sign-in $id")

    private inline fun <T> authenticator(block: () -> T): T =
        try {
            block()
        } catch (e: AuthenticatorException) {
            throw HostCallException(PluginErrorCode.UNAVAILABLE, "MicroG could not issue a token: ${e.message}")
        } catch (e: OperationCanceledException) {
            throw HostCallException(PluginErrorCode.UNAVAILABLE, "MicroG cancelled the token request")
        } catch (e: SecurityException) {
            throw HostCallException(PluginErrorCode.SIGN_IN_EXPIRED, "The chosen account is not available to Milkbeat")
        }

    // Ids are encoded so none can spell another binding's key: '/' separates plugin and method, '#' the fields.
    private fun prefix(pluginId: String) = URLEncoder.encode(pluginId, "UTF-8") + "/"

    private fun base(
        pluginId: String,
        methodId: String,
    ) = prefix(pluginId) + URLEncoder.encode(methodId, "UTF-8")

    private fun key(
        pluginId: String,
        methodId: String,
    ) = stringPreferencesKey(base(pluginId, methodId))

    private fun signerKey(
        pluginId: String,
        methodId: String,
    ) = stringPreferencesKey(base(pluginId, methodId) + "#signer")

    private fun identitiesKey(
        pluginId: String,
        methodId: String,
    ) = stringPreferencesKey(base(pluginId, methodId) + "#identities")

    // Which sign-in wrote the binding; it outlasts the pending marker, so that sign-in can still undo it once confirmed.
    private fun nonceKey(
        pluginId: String,
        methodId: String,
    ) = stringPreferencesKey(base(pluginId, methodId) + "#nonce")

    private fun pendingKey(
        pluginId: String,
        methodId: String,
    ) = stringPreferencesKey(base(pluginId, methodId) + PENDING_SUFFIX)
}
