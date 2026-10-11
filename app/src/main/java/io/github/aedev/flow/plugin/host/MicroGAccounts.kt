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
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

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
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        gateway: MicroGAccountGateway,
    ) : this(gateway, context.pluginAndroidAccounts, Dispatchers.IO)

    fun supported(): Boolean = gateway.serves()

    fun installed(): Boolean = gateway.installed()

    private val attempts = ConcurrentHashMap<String, Mutex>()

    /** Runs one sign-in attempt for a plugin's method at a time, so two screens never interleave their bindings. */
    suspend fun <T> signingIn(
        pluginId: String,
        methodId: String,
        attempt: suspend () -> T,
    ): T = attempts.getOrPut(key(pluginId, methodId).name) { Mutex() }.withLock { attempt() }

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
        val nonce = UUID.randomUUID().toString()
        // Recorded before the write: until the write lands the stored record carries an older nonce, which fails.
        provisional[key(plugin.id, method.id).name] = nonce
        try {
            store.edit {
                it[key(plugin.id, method.id)] = accountName
                it[signerKey(plugin.id, method.id)] = plugin.signerFingerprint
                it[identitiesKey(plugin.id, method.id)] = method.identities.joinToString(",") { identity -> identity.name }
                it[pendingKey(plugin.id, method.id)] = nonce
            }
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
        store.edit {
            check(it[pendingKey(pluginId, methodId)] == nonce) { "The binding was replaced or ended meanwhile" }
            it.remove(pendingKey(pluginId, methodId))
        }
        provisional.remove(key(pluginId, methodId).name, nonce)
    }

    /** Forgets the binding the sign-in that wrote [nonce] left unfinished, and no other. */
    suspend fun forget(
        pluginId: String,
        methodId: String,
        nonce: String,
    ) {
        store.edit { if (it[pendingKey(pluginId, methodId)] == nonce) it.removeBinding(pluginId, methodId) }
        provisional.remove(key(pluginId, methodId).name, nonce)
    }

    private suspend fun forget(
        pluginId: String,
        methodId: String,
    ) {
        store.edit { it.removeBinding(pluginId, methodId) }
        provisional.remove(key(pluginId, methodId).name)
    }

    private fun MutablePreferences.removeBinding(
        pluginId: String,
        methodId: String,
    ) {
        remove(key(pluginId, methodId))
        remove(signerKey(pluginId, methodId))
        remove(identitiesKey(pluginId, methodId))
        remove(pendingKey(pluginId, methodId))
    }

    /** Every binding of [pluginId], as its sign-out or removal ends them. */
    suspend fun forgetAll(pluginId: String) {
        store.edit { preferences ->
            preferences
                .asMap()
                .keys
                .filter { it.name.startsWith("$pluginId/") }
                .forEach { preferences.remove(it) }
        }
        provisional.keys.removeIf { it.startsWith("$pluginId/") }
    }

    internal suspend fun token(
        plugin: InstalledPlugin,
        request: AccountTokenRequest,
    ): AccountToken {
        val method = method(plugin, request.method)
        if (request.identity !in method.identities) {
            throw HostCallException(PluginErrorCode.UNSUPPORTED, "${method.id} does not declare ${request.identity}")
        }
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
        // A sign-out or removal while MicroG answered ends the binding: its token is not handed out.
        if (runCatching { boundAccount(plugin, method, request.identity) }.getOrNull() != account) {
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

    private fun key(
        pluginId: String,
        methodId: String,
    ) = stringPreferencesKey("$pluginId/$methodId")

    private fun signerKey(
        pluginId: String,
        methodId: String,
    ) = stringPreferencesKey("$pluginId/$methodId#signer")

    private fun identitiesKey(
        pluginId: String,
        methodId: String,
    ) = stringPreferencesKey("$pluginId/$methodId#identities")

    private fun pendingKey(
        pluginId: String,
        methodId: String,
    ) = stringPreferencesKey("$pluginId/$methodId#pending")
}
