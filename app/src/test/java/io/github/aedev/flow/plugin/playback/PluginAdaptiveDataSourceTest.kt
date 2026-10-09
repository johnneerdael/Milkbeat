package io.github.aedev.flow.plugin.playback

import android.app.Application
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.ByteRange
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginAdaptiveDataSourceTest {
    @Test
    fun `expired adaptive URLs do not prevent an offline cached range`() {
        val initial = resolved("https://cdn.example/old-audio", "https://cdn.example/old-video", 0)
        var renewals = 0
        val binding =
            BoundPluginAudio(initial, {
                renewals++
                throw IOException("Offline")
            })
        val directory = Files.createTempDirectory("adaptive-offline-cache").toFile()

        @Suppress("DEPRECATION")
        val cache = SimpleCache(directory, NoOpCacheEvictor())
        try {
            val key = "youtube:matched:251:123"
            val hole = cache.startReadWrite(key, 0, 4)
            val file = cache.startFile(key, 0, 4)
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            cache.commitFile(file, 4)
            cache.releaseHoleSpan(hole)
            val source = pluginAdaptiveDataSourceFactory(OkHttpClient(), binding, cache, {}, { listOf("cdn.example") }).createDataSource()
            try {
                source.open(
                    DataSpec
                        .Builder()
                        .setUri(initial.stream.audioFormat!!.url)
                        .setLength(4)
                        .build(),
                )
                val bytes = ByteArray(4)
                assertThat(source.read(bytes, 0, 4)).isEqualTo(4)
                assertThat(bytes).isEqualTo(byteArrayOf(1, 2, 3, 4))
                assertThat(renewals).isEqualTo(0)
            } finally {
                source.close()
            }
        } finally {
            cache.release()
            directory.deleteRecursively()
        }
    }

    private fun resolved(
        audioUrl: String,
        videoUrl: String,
        valid: Long = Long.MAX_VALUE,
    ): ResolvedAudio {
        val sound =
            MediaFormat(
                "251:123",
                FormatType.AUDIO,
                audioUrl,
                "audio/webm",
                codecs = "opus",
                initRange = ByteRange(0, 258),
                indexRange = ByteRange(259, 14537),
            )
        val video =
            MediaFormat(
                "313:124",
                FormatType.VIDEO,
                videoUrl,
                "video/webm",
                codecs = "vp9",
                width = 3840,
                height = 2160,
                initRange = ByteRange(0, 220),
                indexRange = ByteRange(221, 27854),
                headers = mapOf("X-Format" to "picture"),
            )
        return ResolvedAudio(
            "youtube",
            TrackDescriptor(EntityRef(EntityKind.TRACK, "matched"), "Matched"),
            AudioStream(
                audioUrl,
                "matched",
                sound.id,
                sound.mimeType,
                headers =
                    mapOf(
                        "User-Agent" to "VISIONOS-fixture",
                        "Referer" to "https://www.youtube.com",
                    ),
                audioFormat = sound,
                video = video,
            ),
            valid,
            false,
        )
    }

    @Test
    fun `selected format opens one HTTPS range with source and rendition headers`() {
        val tls = PluginSabrDataSourceTest().loopbackTls()
        mockwebserver3.MockWebServer().use { server ->
            server.useHttps(tls.first.socketFactory)
            server.start()
            server.enqueue(
                mockwebserver3.MockResponse
                    .Builder()
                    .code(206)
                    .addHeader("Content-Range", "bytes 221-224/30000")
                    .body("test")
                    .build(),
            )
            val initial = resolved(server.url("/audio").toString(), server.url("/video").toString())
            val binding = BoundPluginAudio(initial, { error("No refresh") })
            val client = OkHttpClient.Builder().sslSocketFactory(tls.first.socketFactory, tls.second).build()
            val factory = pluginAdaptiveDataSourceFactory(client, binding, null, {}, { listOf("localhost") })
            val source = factory.createDataSource()
            source.open(
                DataSpec
                    .Builder()
                    .setUri(initial.stream.video!!.url)
                    .setPosition(221)
                    .setLength(4)
                    .build(),
            )
            source.close()
            val request = server.takeRequest()
            assertThat(request.method).isEqualTo("GET")
            assertThat(request.headers["Range"]).isEqualTo("bytes=221-224")
            assertThat(request.headers["User-Agent"]).isEqualTo("VISIONOS-fixture")
            assertThat(request.headers["X-Format"]).isEqualTo("picture")
            assertThat(server.requestCount).isEqualTo(1)
        }
    }

    @Test
    fun `URL renewal keeps the same rendition and byte ranges without reusing an expired URL`() {
        val initial = resolved("https://cdn.example/old-audio", "https://cdn.example/old-video", 0)
        val next = resolved("https://cdn.example/new-audio", "https://cdn.example/new-video")
        val binding = BoundPluginAudio(initial, { next })
        val requests = mutableListOf<String>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests += chain.request().url.toString()
                    okhttp3.Response
                        .Builder()
                        .request(chain.request())
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("data".toResponseBody())
                        .build()
                }.build()
        val source = pluginAdaptiveDataSourceFactory(client, binding, null, {}, { listOf("cdn.example") }).createDataSource()
        source.open(DataSpec.Builder().setUri(initial.stream.audioFormat!!.url).build())
        source.close()
        assertThat(requests).containsExactly(next.stream.audioFormat!!.url)
    }

    @Test
    fun `ungranted metadata cannot leave the device`() {
        val initial = resolved("https://forbidden.example/audio", "https://forbidden.example/video")
        val binding = BoundPluginAudio(initial, { initial })
        val source = pluginAdaptiveDataSourceFactory(OkHttpClient(), binding, null, {}, { listOf("cdn.example") }).createDataSource()
        assertThat(runCatching { source.open(DataSpec.Builder().setUri(initial.stream.audioFormat!!.url).build()) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
    }
}
