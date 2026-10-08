package io.github.aedev.flow.plugin.catalog

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperation
import javax.inject.Inject
import javax.inject.Singleton

/** Neither a metadata plugin nor a music folder backs this page; it offers to add one. */
class NoMetadataPluginException : Exception("No music plugin or folder backs this page")

/**
 * Calls into metadata plugins on behalf of one music tab or page: [scoped] gives one plugin's
 * catalog. A call that says the sign-in expired marks the account expired, so the pages ask the
 * listener to sign in again.
 */
@Singleton
class PluginMetadataProvider
    @Inject
    constructor(
        private val host: PluginHost,
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
    ) {
        fun scoped(pluginId: String): ScopedPluginCatalog = ScopedPluginCatalog(this, pluginId)

        internal fun accountFor(pluginId: String): Flow<ProviderAccount> =
            combine(registry.state, accounts.accounts) { registry, accounts ->
                if (registry.plugin(pluginId) == null) ProviderAccount.Anonymous else accounts[pluginId] ?: ProviderAccount.Anonymous
            }.distinctUntilChanged()

        /** Which installation of [pluginId] answers, or null while none is enabled; a reinstall changes it. */
        internal fun installationOf(pluginId: String): Flow<Any?> =
            registry.state
                .map { state -> state.plugin(pluginId)?.let { it.installedAtMs to it.manifest.versionCode } }
                .distinctUntilChanged()

        /** Calls [operation] on [plugin], asking for its account first when it is not known yet. */
        internal suspend fun <Request, Response> callFor(
            plugin: String,
            operation: PluginOperation<Request, Response>,
            request: Request,
        ): Result<Response> {
            if (accounts.accounts.value[plugin] == null) {
                try {
                    accounts.refresh(plugin)
                } catch (error: Exception) {
                    if (error is kotlinx.coroutines.CancellationException) throw error
                }
            }
            return try {
                Result.success(host.call(plugin, operation, request))
            } catch (e: PluginCallException) {
                if (e.error.code == PluginErrorCode.SIGN_IN_EXPIRED) accounts.expired(plugin)
                Result.failure(e)
            }
        }
    }

/** What to tell the listener about a failed plugin call: the plugin's own words when it gave some. */
val Throwable.listenerMessage: String?
    get() = (this as? PluginCallException)?.error?.let { it.userMessage ?: it.message } ?: message
