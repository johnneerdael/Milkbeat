package io.github.aedev.flow.plugin.host

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.HttpBodyEncoding
import nl.neerdael.milkbeat.plugin.HttpRequest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import java.util.Base64

class PluginHttpTest {
    @Test
    fun `binary upload and download preserve every byte`() =
        runTest {
            val upload = ByteArray(256) { it.toByte() }
            val download = upload.reversedArray()
            var sent = byteArrayOf()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        val buffer = Buffer()
                        chain.request().body!!.writeTo(buffer)
                        sent = buffer.readByteArray()
                        assertThat(chain.request().header("Content-Type")).isEqualTo("image/png")
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("OK")
                            .body(download.toResponseBody("image/png".toMediaType()))
                            .build()
                    }.build()
            val response =
                PluginHttp(client, { listOf("images.example.com") }, okhttp3.Dns.SYSTEM).fetch(
                    HttpRequest(
                        url = "https://images.example.com/cover",
                        method = "POST",
                        headers = mapOf("Content-Type" to "image/png"),
                        body = Base64.getEncoder().encodeToString(upload),
                        bodyEncoding = HttpBodyEncoding.BASE64,
                        responseEncoding = HttpBodyEncoding.BASE64,
                    ),
                )
            assertThat(sent).isEqualTo(upload)
            assertThat(Base64.getDecoder().decode(response.body)).isEqualTo(download)
        }

    @Test
    fun `binary requests still require HTTPS and granted hosts`() =
        runTest {
            var called = false
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor {
                        called = true
                        error("Network must not be reached")
                    }.build()
            val http = PluginHttp(client, { listOf("images.example.com") }, okhttp3.Dns.SYSTEM)
            for (url in listOf("http://images.example.com/cover", "https://other.example.com/cover")) {
                val failure =
                    runCatching {
                        http.fetch(HttpRequest(url = url, responseEncoding = HttpBodyEncoding.BASE64))
                    }.exceptionOrNull()
                assertThat(failure).isInstanceOf(PluginHttpException::class.java)
            }
            assertThat(called).isFalse()
        }
}
