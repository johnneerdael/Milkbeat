package io.github.aedev.flow.plugin.install

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nl.neerdael.milkbeat.plugin.PLUGIN_API_VERSION
import nl.neerdael.milkbeat.plugin.PLUGIN_FORMAT_VERSION
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

// The plugin publisher commits each release's list and download catalog to the app repository.
private const val PUBLISHED_BASE = "https://raw.githubusercontent.com/johnneerdael/Milkbeat/main/"
private val PublishedListUrl = "${PUBLISHED_BASE}plugins/published.json".toHttpUrl()
private val PublishedCatalogUrl = "${PUBLISHED_BASE}app/src/main/assets/plugin-download-catalog.json".toHttpUrl()

internal val PublishedJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class PublishedPlugins(
    val plugins: List<PublishedPlugin> = emptyList(),
)

@Serializable
internal data class PublishedPlugin(
    val id: String,
    val version: String,
    val versionCode: Int,
    val fingerprint: String,
    val code: String,
    val sha256: String,
    /** The release's `api.min` and container `format`; lists written before they were published mean the first of each. */
    val apiMin: Int = 1,
    val format: Int = 1,
) {
    /** This Milkbeat would refuse the package, so it is neither downloaded nor offered until the app is updated. */
    val requiresNewerApp: Boolean get() = apiMin > PLUGIN_API_VERSION || format > PLUGIN_FORMAT_VERSION
}

/** What the plugin publisher has out right now: each plugin's current release and every download code. */
internal class Publication(
    val plugins: PublishedPlugins,
    val catalog: Map<String, PluginDownloadCode>,
) {
    /** [pluginId]'s current release, when the publisher lists one with a download for it. */
    fun currentRelease(pluginId: String): PublishedPlugin? =
        plugins.plugins
            .firstOrNull { it.id == pluginId }
            ?.takeIf { catalog[it.code]?.id == pluginId }

    /** The download of [pluginId]'s current release, when the publisher lists one. */
    fun currentUrl(pluginId: String): String? = currentRelease(pluginId)?.let { catalog.getValue(it.code).url }
}

/** Reads the publisher's current list and catalog from the app repository. */
@Singleton
class PluginPublication
    @Inject
    constructor(
        private val client: OkHttpClient,
    ) {
        internal suspend fun current(): Publication =
            coroutineScope {
                val list = async { downloadPublished(client, PublishedListUrl) }
                val catalog = async { downloadPublished(client, PublishedCatalogUrl) }
                Publication(
                    PublishedJson.decodeFromString<PublishedPlugins>(list.await().decodeToString()),
                    decodePluginDownloadCatalog(catalog.await().decodeToString()),
                )
            }
    }
