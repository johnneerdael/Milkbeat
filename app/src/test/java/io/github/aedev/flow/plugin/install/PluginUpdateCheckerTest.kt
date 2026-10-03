package io.github.aedev.flow.plugin.install

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.ui.tv.screens.settings.PluginUpdatesState
import io.github.aedev.flow.ui.tv.screens.settings.without
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PluginUpdateCheckerTest {
    private val signer = "39dca3d132c56262c0873ec96faf22cc8ed9ca30c7ed1f135049f8570b943adc"

    private fun installed(
        id: String,
        versionCode: Int,
        fingerprint: String = signer,
    ) = InstalledPlugin(
        PluginManifest(1, ApiRange(1, 2), id, "Plugin $id", "0.$versionCode", versionCode, roles = Roles(audio = AudioRole(setOf(id)))),
        fingerprint,
        "https://buzzheavier.com/old$id",
        0,
        emptyList(),
        emptyList(),
    )

    private fun published(
        id: String,
        versionCode: Int,
        code: String,
        fingerprint: String = signer,
    ) = PublishedPlugin(id = id, version = "0.$versionCode", versionCode = versionCode, fingerprint = fingerprint, code = code)

    private val catalog =
        mapOf(
            "494" to PluginDownloadCode("494", "yt", "YouTube Music", "https://buzzheavier.com/zd643kjppfeu"),
            "981" to PluginDownloadCode("981", "spotify", "Spotify", "https://buzzheavier.com/dr3519gvljk0"),
        )

    @Test
    fun `a newer version by the same author is offered with its download`() {
        val updates =
            availableUpdates(
                installed = listOf(installed("yt", versionCode = 4)),
                published = PublishedPlugins(listOf(published("yt", versionCode = 6, code = "494"))),
                catalog = catalog,
            )

        assertThat(updates).containsExactly(PluginUpdate("yt", "Plugin yt", "0.6", 6, "https://buzzheavier.com/zd643kjppfeu"))
    }

    @Test
    fun `the same or an older version is not an update`() {
        val updates =
            availableUpdates(
                installed = listOf(installed("yt", versionCode = 6), installed("spotify", versionCode = 7)),
                published =
                    PublishedPlugins(listOf(published("yt", versionCode = 6, code = "494"), published("spotify", 5, "981"))),
                catalog = catalog,
            )

        assertThat(updates).isEmpty()
    }

    @Test
    fun `a different author, a missing download or a mismatched download is not offered`() {
        val updates =
            availableUpdates(
                installed = listOf(installed("yt", 4), installed("spotify", 4), installed("beatport", 1)),
                published =
                    PublishedPlugins(
                        listOf(
                            published("yt", versionCode = 6, code = "494", fingerprint = "someone-else"),
                            published("spotify", versionCode = 5, code = "000"),
                            published("beatport", versionCode = 3, code = "981"),
                        ),
                    ),
                catalog = catalog,
            )

        assertThat(updates).isEmpty()
    }

    @Test
    fun `a plugin the publisher does not list has no update`() {
        val updates = availableUpdates(listOf(installed("local", 1)), PublishedPlugins(), catalog)

        assertThat(updates).isEmpty()
    }

    @Test
    fun `installing an update removes it from the findings`() {
        val found =
            PluginUpdatesState.Checked(
                listOf(
                    PluginUpdate("yt", "YouTube Music", "0.6", 6, "https://buzzheavier.com/zd643kjppfeu"),
                    PluginUpdate("spotify", "Spotify", "0.5", 5, "https://buzzheavier.com/dr3519gvljk0"),
                ),
            )

        assertThat((found.without(installed("yt", versionCode = 6)) as PluginUpdatesState.Checked).updates.map { it.pluginId })
            .containsExactly("spotify")
        assertThat((found.without(installed("yt", versionCode = 5)) as PluginUpdatesState.Checked).updates).hasSize(2)
        assertThat(PluginUpdatesState.Checking.without(installed("yt", 6))).isEqualTo(PluginUpdatesState.Checking)
    }
}
