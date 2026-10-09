package io.github.aedev.flow.plugin.install

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.registry.InstalledPlugin
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
    val sha256: String,
)

/** What a check found: updates this Milkbeat can install, and newer releases that need a newer Milkbeat first. */
data class PluginUpdates(
    val installable: List<PluginUpdate> = emptyList(),
    val requiresAppUpdate: List<PluginUpdate> = emptyList(),
)

/**
 * Finds newer versions of the installed plugins in the publisher's current list. Only finding is done
 * here: an update installs through [PluginInstaller], which verifies it is the release offered, its
 * signature and author, and asks the listener like any other install.
 */
@Singleton
class PluginUpdateChecker
    @Inject
    constructor(
        private val publication: PluginPublication,
        @ApplicationContext context: Context,
    ) {
        private val preview = context.packageName.endsWith(".nightly")

        suspend fun check(installed: List<InstalledPlugin>): PluginUpdates {
            if (preview) return PluginUpdates()
            val current =
                try {
                    publication.current()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    throw PluginInstallException(cause = e, messageResource = R.string.tv_plugins_update_check_failed)
                }
            return availableUpdates(installed, current.plugins, current.catalog)
        }
    }

/**
 * The installed plugins the publisher has a newer version of, by the same author, with a download for it.
 * A release whose minimum API or container format is beyond this build is set apart, never downloaded;
 * the package reader refuses it regardless.
 */
internal fun availableUpdates(
    installed: List<InstalledPlugin>,
    published: PublishedPlugins,
    catalog: Map<String, PluginDownloadCode>,
): PluginUpdates {
    val found =
        installed.mapNotNull { plugin ->
            val latest = published.plugins.firstOrNull { it.id == plugin.id } ?: return@mapNotNull null
            if (latest.versionCode <= plugin.manifest.versionCode) return@mapNotNull null
            if (!latest.fingerprint.equals(plugin.signerFingerprint, ignoreCase = true)) return@mapNotNull null
            val download = catalog[latest.code]?.takeIf { it.id == plugin.id } ?: return@mapNotNull null
            latest to PluginUpdate(plugin.id, plugin.manifest.name, latest.version, latest.versionCode, download.url, latest.sha256)
        }
    val (blocked, installable) = found.partition { (latest, _) -> latest.requiresNewerApp }
    return PluginUpdates(installable.map { it.second }, blocked.map { it.second })
}
