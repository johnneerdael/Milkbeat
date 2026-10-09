package io.github.aedev.flow.plugin.install

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.pkg.PluginPackage
import io.github.aedev.flow.plugin.pkg.PluginPackageReader
import io.github.aedev.flow.plugin.pkg.signedTestPackage
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.runBlocking
import nl.neerdael.milkbeat.plugin.PLUGIN_API_VERSION
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PluginInstallerCodeTest {
    private val fixture = checkNotNull(javaClass.getResourceAsStream("/plugins/fixture-signed.mbplugin")).use { it.readBytes() }

    private fun serveFixture(request: Request): Response = serve(request, fixture)

    private fun serve(
        request: Request,
        bytes: ByteArray,
    ): Response {
        val response =
            Response
                .Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
        return when {
            request.url.host == "ts.buzzheavier.com" -> {
                response.body(bytes.toResponseBody("application/octet-stream".toMediaType()))
            }

            request.url.encodedPath.endsWith("/download") -> {
                response.header("HX-Redirect", "https://ts.buzzheavier.com/d/fixture").body(ByteArray(0).toResponseBody())
            }

            else -> {
                val html = "<a hx-get='${request.url.encodedPath}/download?t=fixture'>Download</a>"
                response.body(html.toResponseBody("text/html".toMediaType()))
            }
        }.build()
    }

    private fun challenge(request: Request): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(403)
            .message("Forbidden")
            .header("cf-mitigated", "challenge")
            .body("<title>Just a moment...</title>".toResponseBody("text/html".toMediaType()))
            .build()

    private fun withInstaller(
        respond: (Request) -> Response = ::serveFixture,
        block: (PluginInstaller) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client = OkHttpClient.Builder().addInterceptor { chain -> respond(chain.request()) }.build()
        try {
            block(PluginInstaller(client, PluginRegistry(context), PluginDownloadCodes(context, PluginPublication(client))))
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `a valid signed package for another plugin is refused before consent`() =
        withInstaller { installer ->
            val failure = assertThrows(PluginInstallException::class.java) { runBlocking { installer.fetch("102") } }
            assertThat(failure.messageResource).isEqualTo(R.string.tv_plugins_code_package_mismatch)
        }

    @Test
    fun `a package for a newer Milkbeat is refused with the API it needs rather than as a broken download`() {
        val api = PLUGIN_API_VERSION + 1
        val manifest =
            """{"format":1,"api":{"min":$api,"target":$api},"id":"dev.example.future","name":"Future","version":"2.0.0",""" +
                """"versionCode":2,"roles":{"audio":{"idSpaces":["future"]}}}"""
        val future = signedTestPackage(mapOf("manifest.json" to manifest, "plugin.js" to "definePlugin({})")).readBytes()
        withInstaller({ serve(it, future) }) { installer ->
            val failure =
                assertThrows(PluginRequiresAppUpdateException::class.java) {
                    runBlocking { installer.fetch("https://buzzheavier.com/future123456") }
                }
            assertThat(failure.pluginName).isEqualTo("Future")
            assertThat(failure.apiMin).isEqualTo(api)
            assertThat(failure.format).isEqualTo(1)
        }
    }

    @Test
    fun `an update installs only the exact release it offered`() =
        withInstaller { installer ->
            val id = PluginPackageReader.read(fixture.inputStream()).manifest.id
            val sha256 = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
            val offered = PluginUpdate(id, "Fixture", "9.9", 99, "https://buzzheavier.com/fixture12345", sha256)

            val failure =
                assertThrows(PluginInstallException::class.java) {
                    runBlocking { installer.fetch(offered.url, offered.copy(sha256 = "0".repeat(64))) }
                }
            assertThat(failure.messageResource).isEqualTo(R.string.tv_plugins_update_mismatch)
            assertThat(runBlocking { installer.fetch(offered.url, offered) }.pack.manifest.id).isEqualTo(id)
        }

    @Test
    fun `a consent kept open for an older version cannot replace a newer one installed meanwhile`() =
        withInstaller { installer ->
            val pack = PluginPackageReader.read(fixture.inputStream())
            val older = PluginPackage(pack.manifest.copy(versionCode = pack.manifest.versionCode - 1), pack.files, pack.signerFingerprint)
            val stale = installer.check(older, "https://buzzheavier.com/older1234567")
            runBlocking { installer.install(installer.check(pack, "https://buzzheavier.com/fixture12345")) }

            val failure = assertThrows(PluginInstallException::class.java) { runBlocking { installer.install(stale) } }

            assertThat(failure.messageResource).isEqualTo(R.string.tv_plugins_newer_installed)
        }

    @Test
    fun `a code whose page keeps a browser challenge asks for a browser and keeps the code's plugin`() =
        withInstaller(::challenge) { installer ->
            val failure = assertThrows(BrowserVerificationRequiredException::class.java) { runBlocking { installer.fetch("102") } }
            assertThat(failure.verification.page).startsWith("https://buzzheavier.com/")
            assertThat(failure.verification.source.pluginId).isNotNull()
        }

    @Test
    fun `a package fetched after a browser check is still held to its code's plugin`() =
        withInstaller { installer ->
            val page = "https://buzzheavier.com/abcdef123456"
            val verification = BrowserVerification(page, PluginDownloadSource(page, "another.plugin"))
            val failure =
                assertThrows(PluginInstallException::class.java) {
                    runBlocking { installer.fetch(verification, "https://ts.buzzheavier.com/d/abcdef123456?v=signed") }
                }
            assertThat(failure.messageResource).isEqualTo(R.string.tv_plugins_code_package_mismatch)
        }

    @Test
    fun `an update that needs a browser check still installs only the release it offered`() =
        withInstaller(::challenge) { challenged ->
            val id = PluginPackageReader.read(fixture.inputStream()).manifest.id
            val offered = PluginUpdate(id, "Fixture", "9.9", 99, "https://buzzheavier.com/fixture12345", "0".repeat(64))
            val verification =
                assertThrows(BrowserVerificationRequiredException::class.java) {
                    runBlocking { challenged.fetch(offered.url, offered) }
                }.verification
            withInstaller { installer ->
                val failure =
                    assertThrows(PluginInstallException::class.java) {
                        runBlocking { installer.fetch(verification, "https://ts.buzzheavier.com/d/fixture12345?v=signed") }
                    }
                assertThat(failure.messageResource).isEqualTo(R.string.tv_plugins_update_mismatch)
            }
        }
}
