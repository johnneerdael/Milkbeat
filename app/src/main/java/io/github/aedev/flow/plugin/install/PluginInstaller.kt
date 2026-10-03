package io.github.aedev.flow.plugin.install

import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.pkg.PluginPackage
import io.github.aedev.flow.plugin.pkg.PluginPackageException
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** A verified plugin waiting for the listener's consent, with what installing it would change. */
class PendingInstall(
    val pack: PluginPackage,
    val sourceUrl: String,
    val installed: InstalledPlugin?,
) {
    val isUpdate: Boolean get() = installed != null

    /** Hosts the plugin wants that the listener has not granted yet: what the consent screen asks about. */
    val newNetwork: List<String> get() = pack.manifest.permissions.network - installed?.grantedNetwork.orEmpty().toSet()
    val newBrowser: List<String> get() = pack.manifest.permissions.browser - installed?.grantedBrowser.orEmpty().toSet()
}

class PluginInstallException(
    message: String? = null,
    cause: Throwable? = null,
    val messageResource: Int? = null,
) : Exception(message, cause)

/**
 * Fetches and verifies plugins, then installs them once the listener agrees. An update must come from
 * the same author key and carry a higher version; it installs without asking unless it wants more.
 */
@Singleton
class PluginInstaller
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val registry: PluginRegistry,
        private val codes: PluginDownloadCodes,
    ) {
        /** Fetches the plugin [url] names; [expectedId], when known, is the only plugin it may turn out to be. */
        suspend fun fetch(
            url: String,
            expectedId: String? = null,
        ): PendingInstall {
            val source = codes.resolve(url)
            val bytes = downloadPlugin(client, source.url)
            val pack =
                withContext(Dispatchers.IO) {
                    try {
                        PluginPackageReader.read(bytes.inputStream())
                    } catch (e: PluginPackageException) {
                        throw PluginInstallException(e.message ?: "Not a valid plugin", e)
                    }
                }
            val expected = expectedId ?: source.pluginId
            if (expected != null && pack.manifest.id != expected) {
                throw PluginInstallException(messageResource = R.string.tv_plugins_code_package_mismatch)
            }
            return check(pack, source.url)
        }

        fun check(
            pack: PluginPackage,
            url: String,
        ): PendingInstall {
            val installed =
                registry.state.value.plugins
                    .firstOrNull { it.id == pack.manifest.id }
            if (installed != null) {
                if (installed.signerFingerprint != pack.signerFingerprint) {
                    throw PluginInstallException("${pack.manifest.name} is signed by a different author than the installed one")
                }
                if (pack.manifest.versionCode < installed.manifest.versionCode) {
                    throw PluginInstallException("${pack.manifest.name} ${pack.manifest.version} is older than the installed version")
                }
            }
            return PendingInstall(pack, url, installed)
        }

        /** Installs with the permissions the manifest asks for; call only after the listener agreed to [PendingInstall.needsConsent]. */
        suspend fun install(pending: PendingInstall): InstalledPlugin =
            registry.install(
                pack = pending.pack,
                sourceUrl = pending.sourceUrl,
                grantedNetwork = pending.pack.manifest.permissions.network,
                grantedBrowser = pending.pack.manifest.permissions.browser,
            )
    }
