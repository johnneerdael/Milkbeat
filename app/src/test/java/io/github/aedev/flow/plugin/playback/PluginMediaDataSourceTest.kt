package io.github.aedev.flow.plugin.playback

import android.app.Application
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginMediaDataSourceTest {
    @Test
    fun `a granted progressive request is served`() {
        val tls = PluginSabrDataSourceTest().loopbackTls()
        mockwebserver3.MockWebServer().use { server ->
            server.useHttps(tls.first.socketFactory)
            server.start()
            server.enqueue(
                mockwebserver3.MockResponse
                    .Builder()
                    .code(200)
                    .body("audio")
                    .build(),
            )
            val client = OkHttpClient.Builder().sslSocketFactory(tls.first.socketFactory, tls.second).build()
            val source = pluginMediaDataSourceFactory(client, null, {}, { listOf("localhost") }, Dns.SYSTEM).createDataSource()
            try {
                assertThat(
                    source.open(
                        DataSpec
                            .Builder()
                            .setUri(server.url("/audio").toString())
                            .setLength(5)
                            .build(),
                    ),
                ).isEqualTo(5)
                val bytes = ByteArray(5)
                assertThat(source.read(bytes, 0, 5)).isEqualTo(5)
                assertThat(bytes.decodeToString()).isEqualTo("audio")
            } finally {
                source.close()
            }
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `a declared range policy still splits a bound open`() {
        val tls = PluginSabrDataSourceTest().loopbackTls()
        mockwebserver3.MockWebServer().use { server ->
            server.useHttps(tls.first.socketFactory)
            server.start()
            repeat(3) { index ->
                server.enqueue(
                    mockwebserver3.MockResponse
                        .Builder()
                        .code(206)
                        .addHeader("Content-Range", "bytes ${index * 4}-${index * 4 + 3}/10")
                        .body("abcd")
                        .build(),
                )
            }
            val client = OkHttpClient.Builder().sslSocketFactory(tls.first.socketFactory, tls.second).build()
            val source = pluginMediaDataSourceFactory(client, null, {}, { listOf("localhost") }, Dns.SYSTEM).createDataSource()
            try {
                val spec =
                    DataSpec
                        .Builder()
                        .setUri(server.url("/audio").toString())
                        .setLength(10)
                        .build()
                        .withRangePolicy(PluginRangePolicy.Declared(4))
                assertThat(source.open(spec)).isEqualTo(10)
                val bytes = ByteArray(10)
                var read = 0
                while (read < bytes.size) {
                    val count = source.read(bytes, read, bytes.size - read)
                    if (count <= 0) break
                    read += count
                }
                assertThat(read).isEqualTo(10)
            } finally {
                source.close()
            }
            assertThat(server.requestCount).isEqualTo(3)
        }
    }

    @Test
    fun `a withdrawn origin is refused before any connection`() {
        val source = pluginMediaDataSourceFactory(OkHttpClient(), null, {}, { listOf("cdn.example") }).createDataSource()
        assertThat(
            runCatching {
                source.open(
                    DataSpec
                        .Builder()
                        .setUri("https://forbidden.example/audio")
                        .setLength(4)
                        .build(),
                )
            }.exceptionOrNull(),
        ).isInstanceOf(IOException::class.java)
    }
}
