package io.github.aedev.flow.plugin.install

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.pkg.PluginPackage
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PendingInstallTest {
    private val signer = "39dca3d132c56262c0873ec96faf22cc8ed9ca30c7ed1f135049f8570b943adc"

    private fun manifest(versionCode: Int) =
        PluginManifest(
            1,
            ApiRange(1, 2),
            "beatport",
            "Beatport",
            "0.1.$versionCode",
            versionCode,
            roles = Roles(audio = AudioRole(setOf("beatport"))),
        )

    private fun pending(
        installedVersion: Int?,
        offeredVersion: Int,
    ) = PendingInstall(
        PluginPackage(manifest(offeredVersion), emptyMap(), signer),
        "https://buzzheavier.com/beatport",
        installedVersion?.let { InstalledPlugin(manifest(it), signer, "https://buzzheavier.com/old", 0, emptyList(), emptyList()) },
    )

    @Test fun theInstalledVersionAgainIsAReinstallNotAnUpdate() {
        val again = pending(installedVersion = 3, offeredVersion = 3)
        assertThat(again.isUpdate).isTrue()
        assertThat(again.isReinstall).isTrue()
    }

    @Test fun aNewerVersionIsAnUpdateAndANewPluginIsNeither() {
        assertThat(pending(installedVersion = 3, offeredVersion = 4).isReinstall).isFalse()
        assertThat(pending(installedVersion = 3, offeredVersion = 4).isUpdate).isTrue()
        assertThat(pending(installedVersion = null, offeredVersion = 4).isReinstall).isFalse()
        assertThat(pending(installedVersion = null, offeredVersion = 4).isUpdate).isFalse()
    }
}
