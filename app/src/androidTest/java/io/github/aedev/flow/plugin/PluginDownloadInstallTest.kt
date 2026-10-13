package io.github.aedev.flow.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.plugin.install.PluginDownloadCodes
import io.github.aedev.flow.plugin.install.PluginInstallException
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.install.downloadPlugin
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class PluginDownloadInstallTest {
    @get:Rule
    val hiltRule = HiltAndroidRule(this)

    @Inject
    lateinit var installer: PluginInstaller

    @Inject
    lateinit var codes: PluginDownloadCodes

    @Inject
    lateinit var client: OkHttpClient

    @Before
    fun inject() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePluginDownloads") == "true")
        hiltRule.inject()
    }

    @Test
    fun registeredCodesFetchVerifiedPackagesFromBuzzheavier() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().context
            val published =
                context.assets.open("published-plugins.json").bufferedReader().use { reader ->
                    Json
                        .parseToJsonElement(reader.readText())
                        .jsonObject
                        .getValue("plugins")
                        .jsonArray
                        .associate { row ->
                            row.jsonObject
                                .getValue("code")
                                .jsonPrimitive.content to row.jsonObject
                        }
                }
            val expected =
                mapOf("102" to "nl.neerdael.beatport", "772" to "nl.neerdael.spotify") +
                    published.mapValues { (_, row) -> row.getValue("id").jsonPrimitive.content }
            expected.forEach { (code, id) ->
                val source = codes.resolve(code)
                val bytes = downloadPlugin(client, source.url)
                val pending = installer.check(PluginPackageReader.read(bytes.inputStream()), source.url)
                published[code]?.let { row ->
                    val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    assertEquals(row.getValue("sha256").jsonPrimitive.content, hash)
                    assertEquals(row.getValue("version").jsonPrimitive.content, pending.pack.manifest.version)
                }
                assertEquals(id, pending.pack.manifest.id)
                assertEquals("39dca3d132c56262c0873ec96faf22cc8ed9ca30c7ed1f135049f8570b943adc", pending.pack.signerFingerprint)
                assertTrue(pending.pack.files.containsKey("plugin.js"))
            }
        }

    @Test
    fun unknownCodeFailsBeforeDownloading() {
        val failure = assertThrows(PluginInstallException::class.java) { runBlocking { installer.fetch("000") } }
        assertEquals(io.github.aedev.flow.R.string.tv_plugins_code_unknown, failure.messageResource)
    }
}
