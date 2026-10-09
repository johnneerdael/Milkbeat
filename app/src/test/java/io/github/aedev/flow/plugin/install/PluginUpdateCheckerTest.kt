package io.github.aedev.flow.plugin.install

import android.content.Context
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.PLUGIN_API_VERSION
import nl.neerdael.milkbeat.plugin.PLUGIN_FORMAT_VERSION
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
    ) = PublishedPlugin(
        id = id,
        version = "0.$versionCode",
        versionCode = versionCode,
        fingerprint = fingerprint,
        code = code,
        sha256 = "sha-$id",
    )

    private val catalog =
        mapOf(
            "494" to PluginDownloadCode("494", "yt", "YouTube Music", "https://buzzheavier.com/zd643kjppfeu"),
            "981" to PluginDownloadCode("981", "spotify", "Spotify", "https://buzzheavier.com/dr3519gvljk0"),
        )

    @Test
    fun `Preview never reads stable publication or replaces its pinned preview provider`() =
        runTest {
            val context = mockk<Context>()
            every { context.packageName } returns "nl.neerdael.milkbeat.nightly"
            val publication = mockk<PluginPublication>()
            val id = "nl.neerdael.youtube-video"
            coEvery { publication.current() } returns
                Publication(
                    PublishedPlugins(listOf(published(id, 7, "932").copy(version = "0.1.0"))),
                    mapOf("932" to PluginDownloadCode("932", id, "YouTube Video", "https://fixture.example/stable")),
                )
            val preview = installed(id, 6).let { it.copy(manifest = it.manifest.copy(version = "0.1.0-preview.6")) }
            assertThat(PluginUpdateChecker(publication, context).check(listOf(preview))).isEqualTo(PluginUpdates())
            coVerify(exactly = 0) { publication.current() }
        }

    @Test
    fun `stable Milkbeat can upgrade the same author's preview provider to its normal release`() =
        runTest {
            val context = mockk<Context>()
            every { context.packageName } returns "nl.neerdael.milkbeat"
            val publication = mockk<PluginPublication>()
            val id = "nl.neerdael.youtube-video"
            coEvery { publication.current() } returns
                Publication(
                    PublishedPlugins(listOf(published(id, 7, "932").copy(version = "0.1.0"))),
                    mapOf("932" to PluginDownloadCode("932", id, "YouTube Video", "https://fixture.example/stable")),
                )
            val preview = installed(id, 6).let { it.copy(manifest = it.manifest.copy(version = "0.1.0-preview.6")) }
            assertThat(PluginUpdateChecker(publication, context).check(listOf(preview)).installable)
                .containsExactly(PluginUpdate(id, "Plugin $id", "0.1.0", 7, "https://fixture.example/stable", "sha-$id"))
            coVerify(exactly = 1) { publication.current() }
        }

    @Test
    fun `a newer version by the same author is offered with its download`() {
        val updates =
            availableUpdates(
                installed = listOf(installed("yt", versionCode = 4)),
                published = PublishedPlugins(listOf(published("yt", versionCode = 6, code = "494"))),
                catalog = catalog,
            )

        assertThat(updates.installable)
            .containsExactly(PluginUpdate("yt", "Plugin yt", "0.6", 6, "https://buzzheavier.com/zd643kjppfeu", "sha-yt"))
        assertThat(updates.requiresAppUpdate).isEmpty()
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

        assertThat(updates).isEqualTo(PluginUpdates())
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

        assertThat(updates).isEqualTo(PluginUpdates())
    }

    @Test
    fun `a plugin the publisher does not list has no update`() {
        val updates = availableUpdates(listOf(installed("local", 1)), PublishedPlugins(), catalog)

        assertThat(updates).isEqualTo(PluginUpdates())
    }

    @Test
    fun `a newer version that needs a newer plugin API waits for an app update`() {
        val updates =
            availableUpdates(
                installed = listOf(installed("yt", versionCode = 4), installed("spotify", versionCode = 4)),
                published =
                    PublishedPlugins(
                        listOf(
                            published("yt", versionCode = 6, code = "494").copy(apiMin = PLUGIN_API_VERSION + 1),
                            published("spotify", versionCode = 5, code = "981").copy(apiMin = PLUGIN_API_VERSION),
                        ),
                    ),
                catalog = catalog,
            )

        assertThat(updates.requiresAppUpdate.map { it.pluginId }).containsExactly("yt")
        assertThat(updates.installable.map { it.pluginId }).containsExactly("spotify")
    }

    @Test
    fun `a newer version in a newer container format waits for an app update`() {
        val updates =
            availableUpdates(
                installed = listOf(installed("yt", versionCode = 4)),
                published =
                    PublishedPlugins(
                        listOf(published("yt", versionCode = 6, code = "494").copy(format = PLUGIN_FORMAT_VERSION + 1)),
                    ),
                catalog = catalog,
            )

        assertThat(updates.installable).isEmpty()
        assertThat(updates.requiresAppUpdate)
            .containsExactly(PluginUpdate("yt", "Plugin yt", "0.6", 6, "https://buzzheavier.com/zd643kjppfeu", "sha-yt"))
    }

    @Test
    fun `a waiting update from another author or without a download is not offered either`() {
        val updates =
            availableUpdates(
                installed = listOf(installed("yt", 4), installed("spotify", 4)),
                published =
                    PublishedPlugins(
                        listOf(
                            published("yt", 6, "494", fingerprint = "someone-else").copy(apiMin = PLUGIN_API_VERSION + 1),
                            published("spotify", 5, "000").copy(apiMin = PLUGIN_API_VERSION + 1),
                        ),
                    ),
                catalog = catalog,
            )

        assertThat(updates).isEqualTo(PluginUpdates())
    }

    @Test
    fun `a published list written before API minimums decodes as the first API and format`() {
        val row =
            """{"id":"yt","name":"YouTube Music","version":"0.6","versionCode":6,"sha256":"s","size":1,"fingerprint":"f","code":"494"}"""
        val legacy = PublishedJson.decodeFromString<PublishedPlugins>("""{"format":1,"plugins":[$row]}""").plugins.single()
        val stated =
            PublishedJson
                .decodeFromString<PublishedPlugins>(
                    """{"format":1,"plugins":[${row.dropLast(1)},"apiMin":${PLUGIN_API_VERSION + 1},"format":2}]}""",
                ).plugins
                .single()

        assertThat(legacy.apiMin).isEqualTo(1)
        assertThat(legacy.format).isEqualTo(1)
        assertThat(legacy.requiresNewerApp).isFalse()
        assertThat(stated.apiMin).isEqualTo(PLUGIN_API_VERSION + 1)
        assertThat(stated.format).isEqualTo(2)
        assertThat(stated.requiresNewerApp).isTrue()
    }

    private val found =
        PluginUpdatesState.Checked(
            listOf(
                PluginUpdate("yt", "YouTube Music", "0.6", 6, "https://buzzheavier.com/zd643kjppfeu", "sha-yt"),
                PluginUpdate("spotify", "Spotify", "0.5", 5, "https://buzzheavier.com/dr3519gvljk0", "sha-spotify"),
            ),
            checked = setOf("yt", "spotify"),
        )

    @Test
    fun `installing an update removes it from the findings`() {
        val current = listOf(installed("spotify", versionCode = 4))

        assertThat(
            (found.forInstalled(current + installed("yt", versionCode = 6)) as PluginUpdatesState.Checked).updates.map { it.pluginId },
        ).containsExactly("spotify")
        assertThat((found.forInstalled(current + installed("yt", versionCode = 5)) as PluginUpdatesState.Checked).updates).hasSize(2)
        assertThat(PluginUpdatesState.Checking.forInstalled(current)).isEqualTo(PluginUpdatesState.Checking)
    }

    @Test
    fun `removing a plugin withdraws its update`() {
        val current = found.forInstalled(listOf(installed("spotify", versionCode = 4))) as PluginUpdatesState.Checked

        assertThat(current.updates.map { it.pluginId }).containsExactly("spotify")
    }

    @Test
    fun `a plugin added after the check voids its findings`() {
        val installed = listOf(installed("yt", 4), installed("spotify", 4), installed("beatport", 1))

        assertThat(found.forInstalled(installed)).isEqualTo(PluginUpdatesState.Idle)
    }
}
