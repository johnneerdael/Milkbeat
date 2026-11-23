package io.github.aedev.flow.plugin.registry

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.Permissions
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PluginConnectionGrantsTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    private fun registry(): PluginRegistry {
        val root = File(context.filesDir, "plugins")
        root.deleteRecursively()
        return PluginRegistry(context)
    }

    private fun plugin(
        id: String = "nl.neerdael.eclipse",
        signerFingerprint: String = "aa",
    ) = InstalledPlugin(
        manifest =
            PluginManifest(
                format = 1,
                api = ApiRange(min = 5, target = 12),
                id = id,
                name = "Eclipse",
                version = "0.1.0",
                versionCode = 1,
                roles = Roles(audio = AudioRole(idSpaces = setOf("eclipse"), match = true)),
                permissions = Permissions(network = listOf("api.eclipsemusic.app"), connections = true),
            ),
        signerFingerprint = signerFingerprint,
        sourceUrl = "https://example.org/plugin",
        installedAtMs = 1,
        grantedNetwork = listOf("api.eclipsemusic.app"),
        grantedBrowser = emptyList(),
    )

    @Test
    fun `an approved origin joins the plugin's grants and a revocation withdraws it`() =
        runTest {
            val registry = registry()
            val installed = registry.installForTest(plugin())
            assertThat(registry.state.value.grantedHosts(installed.id)).containsExactly("api.eclipsemusic.app")
            registry.approveConnection(installed, "music.example.org", "org.example.music")
            assertThat(registry.state.value.grantedHosts(installed.id))
                .containsExactly("api.eclipsemusic.app", "music.example.org")
            registry.revokeConnection(installed.id, "music.example.org")
            assertThat(registry.state.value.grantedHosts(installed.id)).containsExactly("api.eclipsemusic.app")
        }

    @Test
    fun `approvals are bound to the plugin and its signer`() =
        runTest {
            val registry = registry()
            val installed = registry.installForTest(plugin())
            registry.approveConnection(installed, "music.example.org", "org.example.music")
            val resigned = installed.copy(signerFingerprint = "bb")
            assertThat(registry.state.value.connectionOrigins(resigned.id, resigned.signerFingerprint)).isEmpty()
            val other = registry.installForTest(plugin(id = "other.plugin"))
            assertThat(registry.state.value.connectionOrigins(other.id, other.signerFingerprint)).isEmpty()
        }

    @Test
    fun `reapproving an add-on id moves its origin instead of accumulating`() =
        runTest {
            val registry = registry()
            val installed = registry.installForTest(plugin())
            registry.approveConnection(installed, "old.example.org", "org.example.music")
            registry.approveConnection(installed, "new.example.org", "org.example.music")
            val origins = registry.state.value.connectionOrigins(installed.id, installed.signerFingerprint)
            assertThat(origins).containsExactly("new.example.org")
        }

    @Test
    fun `removing a plugin clears its approvals with it`() =
        runTest {
            val registry = registry()
            val installed = registry.installForTest(plugin())
            registry.approveConnection(installed, "music.example.org", "org.example.music")
            registry.remove(installed.id)
            assertThat(registry.state.value.connections).isEmpty()
        }
}

private suspend fun PluginRegistry.installForTest(plugin: InstalledPlugin): InstalledPlugin =
    install(
        pack =
            io.github.aedev.flow.plugin.pkg.PluginPackage(
                manifest = plugin.manifest,
                files = mapOf("plugin.js" to ByteArray(0)),
                signerFingerprint = plugin.signerFingerprint,
            ),
        sourceUrl = plugin.sourceUrl,
        grantedNetwork = plugin.grantedNetwork,
        grantedBrowser = plugin.grantedBrowser,
    )
