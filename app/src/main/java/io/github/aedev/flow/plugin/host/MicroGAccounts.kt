package io.github.aedev.flow.plugin.host

import android.accounts.AuthenticatorException
import android.accounts.OperationCanceledException
import android.content.Context
import android.content.Intent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.safePreferencesDataStore
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.plugin.AccountIdentity
import nl.neerdael.milkbeat.plugin.AccountToken
import nl.neerdael.milkbeat.plugin.AccountTokenInvalidation
import nl.neerdael.milkbeat.plugin.AccountTokenRequest
import nl.neerdael.milkbeat.plugin.AndroidAccountMethod
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import javax.inject.Inject
import javax.inject.Singleton

private val Context.pluginAndroidAccounts: DataStore<Preferences> by safePreferencesDataStore(name = "plugin_android_accounts")

/**
 * The MicroG-RE-TV account each plugin's `androidAccount` sign-in is bound to, and the tokens it asks
 * for. Milkbeat keeps only the account name and the signer of the plugin it was chosen for; MicroG
 * keeps the grants and caches the tokens. A plugin never names the account itself, so it reaches only
 * the one the listener picked for it, and a plugin from another signer under the same id reaches none.
 * Sign-out and removal forget the binding.
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

    fun installed(): Boolean = gateway.installed()

    fun chooseAccountIntent(): Intent = gateway.chooseAccountIntent()

    /** The sign-in screen's request for [identity], which may answer with MicroG's prompt. */
    suspend fun request(
        accountName: String,
        identity: AccountIdentity,
    ): MicroGTokenResult = withContext(io) { gateway.token(accountName, identity) }

    suspend fun bind(
        plugin: InstalledPlugin,
        methodId: String,
        accountName: String,
    ) {
        store.edit {
            it[key(plugin.id, methodId)] = accountName
            it[signerKey(plugin.id, methodId)] = plugin.signerFingerprint
        }
    }

    suspend fun forget(
        pluginId: String,
        methodId: String,
    ) {
        store.edit {
            it.remove(key(pluginId, methodId))
            it.remove(signerKey(pluginId, methodId))
        }
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
    }

    internal suspend fun token(
        plugin: InstalledPlugin,
        request: AccountTokenRequest,
    ): AccountToken {
        val method = method(plugin, request.method)
        if (request.identity !in method.identities) {
            throw HostCallException(PluginErrorCode.UNSUPPORTED, "${method.id} does not declare ${request.identity}")
        }
        val stored = store.data.first()
        val account = stored[key(plugin.id, method.id)]
        if (account == null || stored[signerKey(plugin.id, method.id)] != plugin.signerFingerprint) {
            if (account != null) forget(plugin.id, method.id)
            throw HostCallException(PluginErrorCode.SIGN_IN_REQUIRED, "No account was chosen for ${method.id}")
        }
        return withContext(io) {
            authenticator {
                if (!gateway.hasAccount(account)) {
                    throw HostCallException(PluginErrorCode.SIGN_IN_EXPIRED, "The chosen account is no longer on this device")
                }
                when (val result = gateway.token(account, request.identity)) {
                    is MicroGTokenResult.Granted -> AccountToken(result.token)

                    is MicroGTokenResult.NeedsConsent -> throw HostCallException(
                        PluginErrorCode.SIGN_IN_EXPIRED,
                        "MicroG asks to confirm ${request.identity} again",
                    )
                }
            }
        }
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
}
