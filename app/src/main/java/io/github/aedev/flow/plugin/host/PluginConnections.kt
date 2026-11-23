package io.github.aedev.flow.plugin.host

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
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
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The host half of a plugin's reviewed external connections (plugin API 12): it owns the consent
 * and the origin grants, the plugin owns its links. Adding a connection reviews the add-on's
 * manifest without credentials, records the approved origin bound to the installed plugin and its
 * signer, and only then hands the link to the plugin. Replacing or removing a connection withdraws
 * its origin, so JSON, media and license transports refuse it again.
 */
@Singleton
class PluginConnections
    @Inject
    constructor(
        private val registry: PluginRegistry,
        private val host: PluginHost,
        private val client: OkHttpClient,
    ) {
        suspend fun list(pluginId: String): List<PluginConnectionRow> = host.call(pluginId, PluginOperations.connectionsList, Unit)

        /** Fetches a candidate add-on's manifest with no plugin credentials and no redirects. */
        suspend fun inspect(url: String): ConnectionManifest = inspectConnectionManifest(client, ConnectionInspectRequest(url))

        /**
         * Approves the reviewed manifest and saves it with the plugin, as one replacement: the
         * connection id carries over so the plugin replaces its row instead of adding a duplicate,
         * and a plugin failure restores the previous destination approval and withdraws the new one
         * rather than leaving the two out of sync.
         */
        suspend fun approve(
            plugin: InstalledPlugin,
            manifest: ConnectionManifest,
            useAccount: Boolean,
            connectionId: String? = null,
        ): PluginConnectionSaved {
            require(plugin.manifest.permissions.connections) { "${plugin.manifest.name} reviews no external connections" }
            val url =
                manifest.url.toHttpUrlOrNull()
                    ?: throw PluginHttpException("The reviewed add-on has no manifest address")
            val origin = if (url.port == 443) url.host else "${url.host}:${url.port}"
            // The reviewed destination must be reachable for the plugin's own confirming fetch.
            val previous =
                registry.state.value.connections.filter {
                    it.pluginId == plugin.id && it.signerFingerprint == plugin.signerFingerprint && it.addonId == manifest.id
                }
            registry.approveConnection(plugin, origin, manifest.id)
            return try {
                host.call(
                    plugin.id,
                    PluginOperations.connectionsSave,
                    PluginConnectionSave(
                        id = connectionId,
                        url = manifest.url,
                        addonId = manifest.id,
                        useAccount = useAccount,
                    ),
                )
            } catch (failure: Throwable) {
                registry.revokeConnection(plugin.id, origin)
                previous.forEach { grant -> registry.approveConnection(plugin, grant.origin, grant.addonId) }
                throw failure
            }
        }

        suspend fun setEnabled(
            pluginId: String,
            id: String,
            enabled: Boolean,
        ): Unit = host.call(pluginId, PluginOperations.connectionsEnabled, PluginConnectionEnabled(id, enabled))

        /** Removes the link with the plugin and withdraws the origin it held. */
        suspend fun remove(
            plugin: InstalledPlugin,
            row: PluginConnectionRow,
        ) {
            host.call(plugin.id, PluginOperations.connectionsRemove, PluginConnectionRemove(row.id))
            rowOrigin(row)?.let { origin -> registry.revokeConnection(plugin.id, origin) }
        }

        suspend fun test(
            pluginId: String,
            id: String,
        ): PluginConnectionTestResult = host.call(pluginId, PluginOperations.connectionsTest, PluginConnectionTest(id))

        private fun rowOrigin(row: PluginConnectionRow): String? =
            row.host
                .toHttpUrlOrNull()
                ?.let { url -> if (url.port == 443) url.host else "${url.host}:${url.port}" }
                ?: row.host.substringBefore('/')
    }
