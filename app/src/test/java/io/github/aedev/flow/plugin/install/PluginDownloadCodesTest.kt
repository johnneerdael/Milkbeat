package io.github.aedev.flow.plugin.install

import android.content.Context
import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.sync.crypto.SyncCrypto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nl.neerdael.milkbeat.plugin.PLUGIN_API_VERSION
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.Base64

class PluginDownloadCodesTest {
    @Test
    fun `the generated encrypted catalog resolves the three registered packages`() {
        val catalog = decodePluginDownloadCatalog(File("src/main/assets/plugin-download-catalog.json").readText())
        assertThat(pluginDownloadSource("102") { catalog }.url).isEqualTo("https://buzzheavier.com/j8i6nbdomf9d")
        assertThat(pluginDownloadSource("772") { catalog }.pluginId).isEqualTo("nl.neerdael.spotify")
        assertThat(pluginDownloadSource("416") { catalog }.pluginId).isEqualTo("nl.neerdael.youtube-music")
    }

    @Test
    fun `every code a plugin was given installs its current release from the live catalog`() {
        val bundled =
            mapOf(
                "416" to PluginDownloadCode("416", "nl.neerdael.youtube-music", "YouTube Music", "https://buzzheavier.com/oldold123456"),
                "494" to PluginDownloadCode("494", "nl.neerdael.youtube-music", "YouTube Music", "https://buzzheavier.com/olderurl1234"),
            )
        val live =
            Publication(
                PublishedPlugins(listOf(PublishedPlugin("nl.neerdael.youtube-music", "0.2.2", 7, "f", "494", "sha"))),
                bundled +
                    (
                        "494" to
                            PluginDownloadCode("494", "nl.neerdael.youtube-music", "YouTube Music", "https://buzzheavier.com/current12345")
                    ) +
                    ("555" to PluginDownloadCode("555", "dev.example.newer", "Newer", "https://buzzheavier.com/newcode12345")),
            )
        assertThat(pluginDownloadSource("416", live) { bundled }.url).isEqualTo("https://buzzheavier.com/current12345")
        assertThat(pluginDownloadSource("494", live) { bundled }.url).isEqualTo("https://buzzheavier.com/current12345")
        assertThat(pluginDownloadSource("555", live) { bundled }.pluginId).isEqualTo("dev.example.newer")
        assertThat(pluginDownloadSource("416", null) { bundled }.url).isEqualTo("https://buzzheavier.com/oldold123456")
    }

    @Test
    fun `preview installs the bundled provider without consulting stable publication`(): Unit =
        runTest {
            val context = mockk<Context>()
            every { context.packageName } returns "nl.neerdael.milkbeat.nightly"
            val assets = mockk<AssetManager>()
            every { context.assets } returns assets
            every { assets.open("plugin-preview-download-catalog.json") } answers {
                File("src/nightly/assets/plugin-preview-download-catalog.json").inputStream()
            }
            val stableUrl = "https://buzzheavier.com/stable123456"
            val publication = mockk<PluginPublication>()
            coEvery { publication.current() } returns
                Publication(
                    PublishedPlugins(listOf(PublishedPlugin("nl.neerdael.youtube-music", "0.2.8", 13, "f", "494", "sha"))),
                    mapOf("494" to PluginDownloadCode("494", "nl.neerdael.youtube-music", "YouTube Music", stableUrl)),
                )
            val source = PluginDownloadCodes(context, publication).resolve(" 494 ")
            val bundled = decodePluginDownloadCatalog(File("src/nightly/assets/plugin-preview-download-catalog.json").readText())
            assertThat(source.url).isEqualTo(bundled.getValue("494").url)
            assertThat(source.pluginId).isEqualTo("nl.neerdael.youtube-music")
            coVerify(exactly = 0) { publication.current() }
            every { context.packageName } returns "nl.neerdael.milkbeat"
            assertThat(PluginDownloadCodes(context, publication).resolve("494").url).isEqualTo(stableUrl)
            coVerify(exactly = 1) { publication.current() }
        }

    @Test
    fun `a code whose current release needs a newer Milkbeat says so before downloading`() {
        val id = "nl.neerdael.youtube-music"
        val entry = PluginDownloadCode("494", id, "YouTube Music", "https://buzzheavier.com/current12345")
        val release = PublishedPlugin(id, "0.3.0", 14, "f", "494", "sha")
        val newer = Publication(PublishedPlugins(listOf(release.copy(apiMin = PLUGIN_API_VERSION + 1))), mapOf("494" to entry))

        val failure = assertThrows(PluginRequiresAppUpdateException::class.java) { pluginDownloadSource("494", newer) { emptyMap() } }

        assertThat(failure.pluginName).isEqualTo("YouTube Music")
        assertThat(failure.apiMin).isEqualTo(PLUGIN_API_VERSION + 1)
        val current = Publication(PublishedPlugins(listOf(release.copy(apiMin = PLUGIN_API_VERSION))), mapOf("494" to entry))
        assertThat(pluginDownloadSource("494", current) { emptyMap() }.url).isEqualTo(entry.url)
    }

    @Test
    fun `ordinary URLs do not require decrypting the catalog`() {
        val source = pluginDownloadSource("  ntsk.app/spot  ") { error("Catalog must stay unloaded") }
        assertThat(source.url).isEqualTo("https://ntsk.app/spot")
        assertThat(source.pluginId).isNull()
    }

    @Test
    fun `unknown and malformed numeric codes never become numeric hostnames`() {
        listOf("000", "42", "1234", "-12").forEach { input ->
            assertThrows(PluginInstallException::class.java) { pluginDownloadSource(input) { emptyMap() } }
        }
    }

    @Test
    fun `leading zero codes are preserved when resolving a valid catalog`() {
        val catalog =
            decodePluginDownloadCatalog(
                sealed("""[{"code":"007","id":"dev.example.one","name":"One","url":"https://example.com/one.mbplugin"}]"""),
            )
        assertThat(pluginDownloadSource(" 007 ") { catalog }.url).isEqualTo("https://example.com/one.mbplugin")
    }

    @Test
    fun `duplicate codes and invalid catalog URLs are rejected`() {
        val one = """{"code":"007","id":"dev.example.one","name":"One","url":"https://example.com/one.mbplugin"}"""
        assertThrows(IllegalArgumentException::class.java) { decodePluginDownloadCatalog(sealed("[$one,$one]")) }
        assertThrows(IllegalArgumentException::class.java) {
            decodePluginDownloadCatalog(sealed("[$one]".replace("https://example.com/one.mbplugin", "javascript:alert(1)")))
        }
    }

    @Test
    fun `tampering with the encrypted catalog fails authentication`() {
        val raw = File("src/main/assets/plugin-download-catalog.json").readText()
        val envelope = Json.parseToJsonElement(raw).jsonObject.toMutableMap()
        val payload = Base64.getDecoder().decode(envelope.getValue("payload").jsonPrimitive.content)
        payload[0] = (payload[0].toInt() xor 1).toByte()
        envelope["payload"] = JsonPrimitive(Base64.getEncoder().encodeToString(payload))
        assertThrows(Exception::class.java) { decodePluginDownloadCatalog(Json.encodeToString(envelope)) }
    }

    private fun sealed(plaintext: String): String {
        val key = SyncCrypto.randomBytes(32)
        val nonce = SyncCrypto.randomNonce()
        val payload = SyncCrypto.seal(key, nonce, plaintext.toByteArray(), "milkbeat/plugin-download-catalog/1".toByteArray())
        val base64 = Base64.getEncoder()
        return """{"format":1,"key":"${base64.encodeToString(
            key,
        )}","nonce":"${base64.encodeToString(nonce)}","payload":"${base64.encodeToString(payload)}"}"""
    }
}
