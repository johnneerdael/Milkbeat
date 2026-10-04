package io.github.aedev.flow.plugin.install

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class PluginInstallerCodeTest {
    @Test
    fun `a valid signed package for another plugin is refused before consent`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = checkNotNull(javaClass.getResourceAsStream("/plugins/fixture-signed.mbplugin")).use { it.readBytes() }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    val response =
                        Response
                            .Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                    when {
                        request.url.host == "ts.buzzheavier.com" -> {
                            response.body(fixture.toResponseBody("application/octet-stream".toMediaType()))
                        }

                        request.url.encodedPath.endsWith("/download") -> {
                            response.header("HX-Redirect", "https://ts.buzzheavier.com/d/fixture").body(ByteArray(0).toResponseBody())
                        }

                        else -> {
                            val html = "<a hx-get='${request.url.encodedPath}/download?t=fixture'>Download</a>"
                            response.body(html.toResponseBody("text/html".toMediaType()))
                        }
                    }.build()
                }.build()
        try {
            val installer = PluginInstaller(client, PluginRegistry(context), PluginDownloadCodes(context))
            val failure = assertThrows(PluginInstallException::class.java) { runBlocking { installer.fetch("102") } }
            assertThat(failure.messageResource).isEqualTo(R.string.tv_plugins_code_package_mismatch)
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `a package fetched after a browser check is still held to its code's plugin`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val fixture = checkNotNull(javaClass.getResourceAsStream("/plugins/fixture-signed.mbplugin")).use { it.readBytes() }
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(fixture.toResponseBody("application/octet-stream".toMediaType()))
                        .build()
                }.build()
        try {
            val installer = PluginInstaller(client, PluginRegistry(context), PluginDownloadCodes(context))
            val verification =
                BrowserVerification(
                    "https://buzzheavier.com/abcdef123456",
                    PluginDownloadSource("https://buzzheavier.com/abcdef123456", "another.plugin"),
                )
            val failure =
                assertThrows(PluginInstallException::class.java) {
                    runBlocking { installer.fetch(verification, "https://ts.buzzheavier.com/d/abcdef123456?v=signed") }
                }
            assertThat(failure.messageResource).isEqualTo(R.string.tv_plugins_code_package_mismatch)
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }

    @Test
    fun `a code whose page keeps a browser challenge asks for a browser and keeps the code's plugin`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(403)
                        .message("Forbidden")
                        .header("cf-mitigated", "challenge")
                        .body("<title>Just a moment...</title>".toResponseBody("text/html".toMediaType()))
                        .build()
                }.build()
        try {
            val installer = PluginInstaller(client, PluginRegistry(context), PluginDownloadCodes(context))
            val failure = assertThrows(BrowserVerificationRequiredException::class.java) { runBlocking { installer.fetch("102") } }
            assertThat(failure.verification.page).startsWith("https://buzzheavier.com/")
            assertThat(failure.verification.source.pluginId).isNotNull()
        } finally {
            client.dispatcher.executorService.shutdown()
        }
    }
}
