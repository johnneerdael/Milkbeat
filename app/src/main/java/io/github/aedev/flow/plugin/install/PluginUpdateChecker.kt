package io.github.aedev.flow.plugin.install

import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** A newer published version of an installed plugin, and the download it installs from. */
data class PluginUpdate(
    val pluginId: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val url: String,
)

/**
 * Finds newer versions of the installed plugins in the publisher's current list. Only finding is done
 * here: an update installs through [PluginInstaller], which verifies its signature and author and asks
 * the listener like any other install.
 */
@Singleton
class PluginUpdateChecker
    @Inject
    constructor(
        private val publication: PluginPublication,
        private val registry: PluginRegistry,
    ) {
        suspend fun check(): List<PluginUpdate> {
            val current =
                try {
                    publication.current()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: PluginInstallException) {
                    throw e
                } catch (e: Exception) {
                    throw PluginInstallException(cause = e, messageResource = R.string.tv_plugins_update_check_failed)
                }
            return availableUpdates(registry.state.value.plugins, current.plugins, current.catalog)
        }
    }

/** The installed plugins the publisher has a newer version of, by the same author, with a download for it. */
internal fun availableUpdates(
    installed: List<InstalledPlugin>,
    published: PublishedPlugins,
    catalog: Map<String, PluginDownloadCode>,
): List<PluginUpdate> =
    installed.mapNotNull { plugin ->
        val latest = published.plugins.firstOrNull { it.id == plugin.id } ?: return@mapNotNull null
        if (latest.versionCode <= plugin.manifest.versionCode) return@mapNotNull null
        if (!latest.fingerprint.equals(plugin.signerFingerprint, ignoreCase = true)) return@mapNotNull null
        val download = catalog[latest.code]?.takeIf { it.id == plugin.id } ?: return@mapNotNull null
        PluginUpdate(plugin.id, plugin.manifest.name, latest.version, latest.versionCode, download.url)
    }
