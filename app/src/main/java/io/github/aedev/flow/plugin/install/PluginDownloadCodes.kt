package io.github.aedev.flow.plugin.install

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.sync.crypto.SyncCrypto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private const val CATALOG_ASSET = "plugin-download-catalog.json"
private const val PREVIEW_CATALOG_ASSET = "plugin-preview-download-catalog.json"
private const val CATALOG_AAD = "milkbeat/plugin-download-catalog/1"

@Singleton
class PluginDownloadCodes
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val publication: PluginPublication,
    ) {
        private val preview = context.packageName.endsWith(".nightly")

        private val catalog by lazy {
            context.assets
                .open(if (preview) PREVIEW_CATALOG_ASSET else CATALOG_ASSET)
                .bufferedReader()
                .use { decodePluginDownloadCatalog(it.readText()) }
        }

        /**
         * A code resolves against the publisher's current catalog when it can be read, so every code a
         * plugin was ever given installs its current release and codes newer than this build work too;
         * offline, the catalog bundled with the app answers. The separate Nightly/Preview app pins
         * downloader codes to its bundled catalog so stable publication cannot replace its test providers.
         */
        internal suspend fun resolve(input: String): PluginDownloadSource =
            withContext(Dispatchers.IO) {
                val live =
                    if (isPluginDownloadCode(input.trim()) && !preview) {
                        try {
                            publication.current()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    } else {
                        null
                    }
                pluginDownloadSource(input, live) {
                    try {
                        catalog
                    } catch (e: Exception) {
                        throw PluginInstallException(cause = e, messageResource = R.string.tv_plugins_catalog_invalid)
                    }
                }
            }
    }

internal data class PluginDownloadSource(
    val url: String,
    val pluginId: String? = null,
)

@Serializable
internal data class PluginDownloadCode(
    val code: String,
    val id: String,
    val name: String,
    val url: String,
)

@Serializable
private data class EncryptedPluginDownloadCatalog(
    val format: Int,
    val key: String,
    val nonce: String,
    val payload: String,
)

internal fun isPluginDownloadCode(value: String): Boolean = value.length == 3 && value.all { it in '0'..'9' }

internal fun pluginDownloadSource(
    value: String,
    live: Publication? = null,
    catalog: () -> Map<String, PluginDownloadCode>,
): PluginDownloadSource {
    val input = value.trim()
    if (isPluginDownloadCode(input)) {
        val entry =
            live?.catalog?.get(input) ?: catalog()[input]
                ?: throw PluginInstallException(messageResource = R.string.tv_plugins_code_unknown)
        val current = live?.currentRelease(entry.id)
        // The current release would be refused after downloading it; say so before fetching anything.
        if (current?.requiresNewerApp == true) throw PluginRequiresAppUpdateException(entry.name, current.apiMin, current.format)
        return PluginDownloadSource(live?.currentUrl(entry.id) ?: entry.url, entry.id)
    }
    if (input.matches(Regex("[+-]?[0-9]+"))) throw PluginInstallException(messageResource = R.string.tv_plugins_input_invalid)
    val url = pluginUrl(input) ?: throw PluginInstallException(messageResource = R.string.tv_plugins_input_invalid)
    return PluginDownloadSource(url.toString())
}

internal fun decodePluginDownloadCatalog(raw: String): Map<String, PluginDownloadCode> {
    val envelope = Json.decodeFromString<EncryptedPluginDownloadCatalog>(raw)
    require(envelope.format == 1)
    val base64 = Base64.getDecoder()
    val key = base64.decode(envelope.key)
    val nonce = base64.decode(envelope.nonce)
    val payload = base64.decode(envelope.payload)
    require(key.size == 32 && nonce.size == SyncCrypto.NONCE_LEN && payload.size >= SyncCrypto.TAG_LEN)
    // The bundled key makes this protection against casual inspection, not a secret vault.
    val plaintext = SyncCrypto.open(key, nonce, payload, CATALOG_AAD.toByteArray())
    // Builds before this one reject unknown entry fields, so the publisher cannot add any until those are retired.
    val entries = PublishedJson.decodeFromString<List<PluginDownloadCode>>(plaintext.toString(Charsets.UTF_8))
    require(
        entries.size <= 1000 && entries.map { it.code }.toSet().size == entries.size && entries.map { it.url }.toSet().size == entries.size,
    )
    require(
        entries.all {
            isPluginDownloadCode(it.code) && it.id.isNotBlank() && it.name.isNotBlank() &&
                pluginUrl(it.url)?.toString() == it.url
        },
    )
    return entries.associateBy { it.code }
}
