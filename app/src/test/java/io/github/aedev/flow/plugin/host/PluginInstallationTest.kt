package io.github.aedev.flow.plugin.host

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.installation
import io.github.aedev.flow.plugin.pluginCodeCachePath
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PluginInstallationTest {
    private fun installed(
        versionCode: Int,
        installedAtMs: Long,
        enabled: Boolean = true,
    ) = InstalledPlugin(
        PluginManifest(
            1,
            ApiRange(1, 2),
            "beatport",
            "Beatport",
            "0.1.$versionCode",
            versionCode,
            roles = Roles(audio = AudioRole(setOf("beatport"))),
        ),
        "signer",
        "https://buzzheavier.com/beatport",
        installedAtMs,
        emptyList(),
        emptyList(),
        enabled,
    )

    @Test fun reinstallingTheSameVersionIsANewInstallationWithItsOwnCompiledCode() {
        val first = installed(versionCode = 3, installedAtMs = 1_000)
        val again = installed(versionCode = 3, installedAtMs = 2_000)

        assertThat(again.installation).isNotEqualTo(first.installation)
        assertThat(pluginCodeCachePath(again)).isNotEqualTo(pluginCodeCachePath(first))
    }

    @Test fun disablingKeepsTheInstallationAndItsCompiledCode() {
        val enabled = installed(versionCode = 3, installedAtMs = 1_000)
        val disabled = installed(versionCode = 3, installedAtMs = 1_000, enabled = false)

        assertThat(disabled.installation).isEqualTo(enabled.installation)
        assertThat(pluginCodeCachePath(disabled)).isEqualTo("plugin-code/beatport/3-1000")
    }
}
