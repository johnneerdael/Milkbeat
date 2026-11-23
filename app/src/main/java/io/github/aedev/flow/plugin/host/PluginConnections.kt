package io.github.aedev.flow.plugin.host

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.plugin.ConnectionInspectRequest
import nl.neerdael.milkbeat.plugin.ConnectionManifest
import nl.neerdael.milkbeat.plugin.PluginConnectionEnabled
import nl.neerdael.milkbeat.plugin.PluginConnectionRemove
import nl.neerdael.milkbeat.plugin.PluginConnectionRow
import nl.neerdael.milkbeat.plugin.PluginConnectionSave
import nl.neerdael.milkbeat.plugin.PluginConnectionSaved
import nl.neerdael.milkbeat.plugin.PluginConnectionTest
import nl.neerdael.milkbeat.plugin.PluginConnectionTestResult
import nl.neerdael.milkbeat.plugin.PluginOperations
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The host half of a plugin's reviewed external connections (plugin API 12): it owns the consent
 * and the origin grants, the plugin owns its links. Adding a connection reviews the add-on's
 * manifest without credentials and hands the approved link to the plugin. Only that manifest is
 * available during confirmation. A successful save records the origin for that connection and
 * signer. Replacing or removing a row withdraws its grant without disturbing other connections.
 */
@Singleton
class PluginConnections
    @Inject
    constructor(
        private val registry: PluginRegistry,
        private val host: PluginHost,
        private val client: OkHttpClient,
    ) {
        private val changes = Mutex()

        suspend fun list(pluginId: String): List<PluginConnectionRow> = host.call(pluginId, PluginOperations.connectionsList, Unit)

        /** Fetches a candidate add-on's manifest with no plugin credentials and no redirects. */
        suspend fun inspect(url: String): ConnectionManifest = inspectConnectionManifest(client, ConnectionInspectRequest(url))

        suspend fun approve(
            plugin: InstalledPlugin,
            manifest: ConnectionManifest,
            useAccount: Boolean,
            connectionId: String? = null,
        ): PluginConnectionSaved =
            changes.withLock {
                require(plugin.manifest.permissions.connections) { "${plugin.manifest.name} reviews no external connections" }
                val url = normalizedManifestUrl(manifest.url)
                val rows = list(plugin.id)
                val previous = rows.firstOrNull { it.id == connectionId }
                if (connectionId != null && previous == null) throw PluginHttpException("This add-on was removed")
                currentCoroutineContext().ensureActive()
                // After the confirmation starts, leaving Settings must not interrupt the registry
                // write after the plugin's row has been persisted. The runtime still limits the call.
                withContext(NonCancellable) {
                    val saved =
                        host.saveReviewedConnection(
                            plugin,
                            url,
                            PluginConnectionSave(id = connectionId, url = url.toString(), addonId = manifest.id, useAccount = useAccount),
                        )
                    if (saved.id.isBlank() || rows.any { it.id == saved.id && it.id != connectionId }) {
                        throw PluginHttpException("The plugin returned an invalid connection ID")
                    }
                    registry.approveConnection(plugin, saved.id, approvedOrigin(url), manifest.id, previous)
                    saved
                }
            }

        suspend fun setEnabled(
            pluginId: String,
            id: String,
            enabled: Boolean,
        ): Unit =
            changes.withLock {
                host.call(pluginId, PluginOperations.connectionsEnabled, PluginConnectionEnabled(id, enabled))
            }

        /** Removes the link with the plugin and withdraws the origin it held. */
        suspend fun remove(
            plugin: InstalledPlugin,
            row: PluginConnectionRow,
        ) = changes.withLock {
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                host.call(plugin.id, PluginOperations.connectionsRemove, PluginConnectionRemove(row.id))
                registry.revokeConnection(plugin, row)
            }
        }

        suspend fun test(
            pluginId: String,
            id: String,
        ): PluginConnectionTestResult = host.call(pluginId, PluginOperations.connectionsTest, PluginConnectionTest(id))
    }
