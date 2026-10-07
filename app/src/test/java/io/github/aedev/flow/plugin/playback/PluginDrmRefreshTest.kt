package io.github.aedev.flow.plugin.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioDrm
import nl.neerdael.milkbeat.plugin.AudioDrmScheme
import nl.neerdael.milkbeat.plugin.AudioStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginDrmRefreshTest {
    private fun audio(
        token: String,
        expires: Long,
        headers: Map<String, String> = emptyMap(),
    ): ResolvedAudio =
        ResolvedAudio(
            "provider",
            TrackDescriptor(EntityRef(EntityKind.TRACK, "song"), "Song"),
            AudioStream(
                "https://media.example/$token",
                "song",
                "aac",
                "application/x-mpegURL",
                drm = AudioDrm(AudioDrmScheme.WIDEVINE, "https://license.example/playback?token=$token", headers),
            ),
            expires,
            false,
        )

    @Test
    fun `expired initial key request and long session renewal use current license credentials`() {
        var now = 20L
        var count = 0
        val old = audio("old", 10, mapOf("Authorization" to "old", "X-Removed" to "old"))
        val current = audio("new", 30, mapOf("Authorization" to "new"))
        val renewed = audio("renewed", 50, mapOf("authorization" to "renewed"))
        val binding =
            BoundPluginAudio(old, {
                count++
                if (count == 1) current else renewed
            }, { now })
        val requests = mutableListOf<Request>()
        val bodies = mutableListOf<ByteArray>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    val buffer = Buffer()
                    request.body!!.writeTo(buffer)
                    bodies += buffer.readByteArray()
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(byteArrayOf(-1, 0, 127).toResponseBody())
                        .build()
                }.build()
        val callback =
            HttpMediaDrmCallback(
                old.stream.drm!!.licenseUrl,
                true,
                pluginDrmDataSourceFactory(client, binding) { listOf("license.example") },
            )
        val challenge = ByteArray(256) { it.toByte() }
        assertThat(callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(challenge, "")).data)
            .isEqualTo(byteArrayOf(-1, 0, 127))
        now = 40
        callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(challenge, ""))
        assertThat(requests.map { it.url.queryParameter("token") }).containsExactly("new", "renewed").inOrder()
        assertThat(requests.map { it.header("Authorization") }).containsExactly("new", "renewed").inOrder()
        requests.forEach {
            assertThat(it.header("X-Removed")).isNull()
            assertThat(it.header("Content-Type")).isEqualTo("application/octet-stream")
        }
        bodies.forEach { assertThat(it).isEqualTo(challenge) }
        assertThat(count).isEqualTo(2)
        assertThat(runBlocking { binding.current() }).isSameInstanceAs(renewed)
    }

    @Test
    fun `concurrent media and license consumers share one expired binding refresh`() =
        runTest {
            val old = audio("old", 0)
            val fresh = audio("new", 100)
            var count = 0
            val binding =
                BoundPluginAudio(old, {
                    count++
                    delay(1)
                    fresh
                }, { 10 })
            val results = (1..8).map { async { binding.current() } }.awaitAll()
            assertThat(count).isEqualTo(1)
            results.forEach { assertThat(it).isSameInstanceAs(fresh) }
        }

    @Test
    fun `concurrent license opens and a media open coalesce the expired resolution`() =
        runBlocking {
            val old = audio("old", 0)
            val fresh = audio("new", 100)
            val count =
                java.util.concurrent.atomic
                    .AtomicInteger()
            val binding =
                BoundPluginAudio(old, {
                    count.incrementAndGet()
                    delay(50)
                    fresh
                }, { 10 })
            val requests = java.util.Collections.synchronizedList(mutableListOf<Request>())
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requests += chain.request()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(200)
                            .message("fixture")
                            .body(byteArrayOf(1).toResponseBody())
                            .build()
                    }.build()
            val callback =
                HttpMediaDrmCallback(
                    old.stream.drm!!.licenseUrl,
                    true,
                    pluginDrmDataSourceFactory(client, binding) { listOf("license.example") },
                )
            val opens =
                (1..6).map {
                    async(Dispatchers.IO) {
                        callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(1, -1), ""))
                    }
                }
            val media = async(Dispatchers.IO) { binding.current() }
            opens.awaitAll()
            assertThat(media.await()).isSameInstanceAs(fresh)
            assertThat(count.get()).isEqualTo(1)
            assertThat(requests).hasSize(6)
            requests.forEach { assertThat(it.url.queryParameter("token")).isEqualTo("new") }
        }

    @Test
    fun `refreshed credentials survive redirects without retargeting their URLs`() {
        val old = audio("old", 0)
        var count = 0
        val fresh = audio("new", 100, mapOf("Authorization" to "new"))
        val binding =
            BoundPluginAudio(old, {
                count++
                fresh
            }, { 10 })
        val requests = mutableListOf<Request>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests += request
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (request.url.queryParameter("token") == "new") 307 else 200)
                        .message("fixture")
                        .header("Location", old.stream.drm!!.licenseUrl)
                        .body(byteArrayOf(1).toResponseBody())
                        .build()
                }.build()
        val callback =
            HttpMediaDrmCallback(
                old.stream.drm!!.licenseUrl,
                true,
                pluginDrmDataSourceFactory(client, binding) { listOf("license.example") },
            )
        callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(-1, 0, 1), ""))
        assertThat(requests.map { it.url.queryParameter("token") }).containsExactly("new", "old").inOrder()
        requests.forEach { assertThat(it.header("Authorization")).isEqualTo("new") }
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `provisioning is never retargeted or given the plugin license credentials`() {
        val old = audio("old", 0, mapOf("Authorization" to "old"))
        var count = 0
        val binding =
            BoundPluginAudio(old, {
                count++
                audio("new", 100)
            }, { 10 })
        val requests = mutableListOf<Request>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    requests += chain.request()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(byteArrayOf(1).toResponseBody())
                        .build()
                }.build()
        var allowed = listOf("license.example", "provision.example")
        val callback = HttpMediaDrmCallback(old.stream.drm!!.licenseUrl, true, pluginDrmDataSourceFactory(client, binding) { allowed })
        val provision = ExoMediaDrm.ProvisionRequest("fixture".toByteArray(), "https://provision.example/provision")
        callback.executeProvisionRequest(C.WIDEVINE_UUID, provision)
        assertThat(requests.single().url.host).isEqualTo("provision.example")
        assertThat(requests.single().header("Authorization")).isNull()
        assertThat(requests.single().header("Content-Type")).startsWith("application/json")
        assertThat(count).isEqualTo(0)
        callback.executeProvisionRequest(
            C.WIDEVINE_UUID,
            ExoMediaDrm.ProvisionRequest("fixture".toByteArray(), old.stream.drm!!.licenseUrl),
        )
        assertThat(requests.last().url.queryParameter("token")).isEqualTo("old")
        assertThat(requests.last().header("Authorization")).isNull()
        assertThat(count).isEqualTo(0)
        allowed = listOf("license.example")
        assertThat(runCatching { callback.executeProvisionRequest(C.WIDEVINE_UUID, provision) }.isFailure).isTrue()
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `new license destinations and redirects must still have current grants`() {
        for (redirect in listOf(false, true)) {
            val old = audio("old", 0)
            val fresh = audio("new", 100, mapOf("Authorization" to "new"))
            val unauthorized =
                ResolvedAudio(
                    fresh.pluginId,
                    fresh.track,
                    fresh.stream.copy(drm = fresh.stream.drm!!.copy(licenseUrl = "https://evil.example/license")),
                    fresh.validUntilMs,
                    false,
                )
            val binding = BoundPluginAudio(old, { if (redirect) fresh else unauthorized }, { 10 })
            val requests = mutableListOf<Request>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        requests += chain.request()
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(307)
                            .message("fixture")
                            .header("Location", "https://evil.example/license")
                            .body(ByteArray(0).toResponseBody())
                            .build()
                    }.build()
            val callback =
                HttpMediaDrmCallback(
                    old.stream.drm!!.licenseUrl,
                    true,
                    pluginDrmDataSourceFactory(client, binding) { listOf("license.example") },
                )
            assertThat(
                runCatching { callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(1), "")) }.isFailure,
            ).isTrue()
            assertThat(requests).hasSize(if (redirect) 1 else 0)
            requests.forEach { assertThat(it.url.host).isEqualTo("license.example") }
        }
    }

    @Test
    fun `protected refresh refuses incompatible renditions and keeps the previous binding`() =
        runTest {
            val old = audio("old", 0)
            val fresh = audio("new", 100)
            for (stream in listOf(
                fresh.stream.copy(drm = null),
                fresh.stream.copy(renditionId = "different"),
                fresh.stream.copy(cacheKey = "different"),
                fresh.stream.copy(mimeType = "audio/mp4"),
                fresh.stream.copy(codecs = "different"),
                fresh.stream.copy(bitrate = 123),
            )) {
                val incompatible = ResolvedAudio(fresh.pluginId, fresh.track, stream, 100, false)
                var replacement = incompatible
                val binding = BoundPluginAudio(old, { replacement }, { 10 })
                assertThat(runCatching { binding.current() }.isFailure).isTrue()
                replacement = fresh
                assertThat(binding.current()).isSameInstanceAs(fresh)
            }
        }
}
