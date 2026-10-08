package io.github.aedev.flow.plugin.install

import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.pkg.PluginPackage
import io.github.aedev.flow.plugin.pkg.PluginPackageException
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.NewerPluginInstalledException
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/** A verified plugin waiting for the listener's consent, with what installing it would change. */
class PendingInstall(
    val pack: PluginPackage,
    val sourceUrl: String,
    val installed: InstalledPlugin?,
) {
    val isUpdate: Boolean get() = installed != null

    /** The installed version again, for example from re-entering its download code: offered as a reinstall, not an update. */
    val isReinstall: Boolean get() = installed != null && pack.manifest.versionCode == installed.manifest.versionCode

    /** Hosts the plugin wants that the listener has not granted yet: what the consent screen asks about. */
    val newNetwork: List<String> get() = pack.manifest.permissions.network - installed?.grantedNetwork.orEmpty().toSet()
    val newBrowser: List<String> get() = pack.manifest.permissions.browser - installed?.grantedBrowser.orEmpty().toSet()

    /** A new plugin, or an update that wants hosts or pages the listener has not granted, must be agreed to first. */
    val needsConsent: Boolean get() = !isUpdate || newNetwork.isNotEmpty() || newBrowser.isNotEmpty()
}

open class PluginInstallException(
    message: String? = null,
    cause: Throwable? = null,
    val messageResource: Int? = null,
) : Exception(message, cause)

/** The plugin's download page wants a browser check; the listener passes it on [page], then [PluginInstaller.fetch] continues. */
class BrowserVerification internal constructor(
    val page: String,
    internal val source: PluginDownloadSource,
    internal val update: PluginUpdate? = null,
)

class BrowserVerificationRequiredException(
    val verification: BrowserVerification,
) : PluginInstallException(messageResource = R.string.tv_plugins_download_verification)

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
        /** Fetches the plugin [url] names; for an [update], only the exact release it offered is accepted. */
        suspend fun fetch(
            url: String,
            update: PluginUpdate? = null,
        ): PendingInstall {
            val source = codes.resolve(url)
            val bytes =
                try {
                    downloadPlugin(client, source.url)
                } catch (e: BuzzheavierChallengeException) {
                    throw BrowserVerificationRequiredException(BrowserVerification(e.page, source, update))
                }
            return verify(bytes, source, update)
        }

        /** Continues [verification] with the file link the browser was handed once it passed. */
        suspend fun fetch(
            verification: BrowserVerification,
            fileUrl: String,
        ): PendingInstall = verify(downloadBuzzheavierFile(client, verification.page, fileUrl), verification.source, verification.update)

        private suspend fun verify(
            bytes: ByteArray,
            source: PluginDownloadSource,
            update: PluginUpdate?,
        ): PendingInstall {
            val pack =
                withContext(Dispatchers.IO) {
                    if (update != null && !sha256(bytes).equals(update.sha256, ignoreCase = true)) {
                        throw PluginInstallException(messageResource = R.string.tv_plugins_update_mismatch)
                    }
                    try {
                        PluginPackageReader.read(bytes.inputStream())
                    } catch (e: PluginPackageException) {
                        throw PluginInstallException(e.message ?: "Not a valid plugin", e)
                    }
                }
            val expected = update?.pluginId ?: source.pluginId
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
            try {
                registry.install(
                    pack = pending.pack,
                    sourceUrl = pending.sourceUrl,
                    grantedNetwork = pending.pack.manifest.permissions.network,
                    grantedBrowser = pending.pack.manifest.permissions.browser,
                )
            } catch (e: NewerPluginInstalledException) {
                throw PluginInstallException(cause = e, messageResource = R.string.tv_plugins_newer_installed)
            }
    }

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
