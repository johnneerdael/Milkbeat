package io.github.aedev.flow.plugin.playback

import android.app.Application
import androidx.media3.common.C
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
class PluginDrmDataSourceTest {
    @Test
    fun `ungranted and insecure license URLs are denied without exposing tokens`() {
        for (url in listOf(
            "http://license.example/license?license_token=secret",
            "https://evil.example/license?license_token=secret",
            "https://user:secret@license.example/license",
            "https://license.example.evil/license?license_token=secret",
        )) {
            val error = runCatching { checkedPluginDrmUrl(url, listOf("license.example")) }.exceptionOrNull()
            assertThat(error).isNotNull()
            assertThat(error!!.message).doesNotContain("secret")
        }
        assertThat(checkedPluginDrmUrl("https://license.example/license", listOf("license.example")).host)
            .isEqualTo("license.example")
    }

    @Test
    fun `Widevine challenge and license bytes survive an approved redirect`() {
        var allowed = listOf("license.example", "approved.example")
        val requests = mutableListOf<okhttp3.Request>()
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
                        .code(if (request.url.host == "license.example") 307 else 200)
                        .message("fixture")
                        .header("Location", "https://approved.example/license")
                        .body(byteArrayOf(0, 127, -1).toResponseBody())
                        .build()
                }.build()
        val callback = HttpMediaDrmCallback("https://license.example/license", true, pluginDrmDataSourceFactory(client) { allowed })
        callback.setKeyRequestProperty("Authorization", "fixture")
        val challenge = ByteArray(256) { it.toByte() }
        val response = callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(challenge, "https://evil.example/manifest"))
        assertThat(response.data).isEqualTo(byteArrayOf(0, 127, -1))
        assertThat(requests.map { it.url.host }).containsExactly("license.example", "approved.example").inOrder()
        assertThat(bodies).hasSize(2)
        bodies.forEach { assertThat(it).isEqualTo(challenge) }
        requests.forEach {
            assertThat(it.method).isEqualTo("POST")
            assertThat(it.header("Authorization")).isEqualTo("fixture")
            assertThat(it.header("Content-Type")).isEqualTo("application/octet-stream")
        }
        allowed = emptyList()
        assertThat(runCatching { callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(challenge, "")) }.isFailure).isTrue()
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `a certificate session opens its license through the bound transport`() {
        // The same transport the standard Widevine path uses: an approved redirect succeeds, and a
        // revoked grant refuses the open before any byte or header leaves the device.
        var allowed = listOf("license.example", "approved.example")
        val requests = mutableListOf<okhttp3.Request>()
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
                        .code(if (request.url.host == "license.example") 307 else 200)
                        .message("fixture")
                        .header("Location", "https://approved.example/license")
                        .body(byteArrayOf(4, 5, 6).toResponseBody())
                        .build()
                }.build()
        val item =
            androidx.media3.common.MediaItem
                .Builder()
                .setUri("music://candidate")
                .setDrmConfiguration(
                    androidx.media3.common.MediaItem.DrmConfiguration
                        .Builder(C.WIDEVINE_UUID)
                        .setLicenseUri("https://license.example/license")
                        .setForceDefaultLicenseUri(true)
                        .build(),
                ).build()
        val callback = checkedLicenseCallback(item, pluginDrmDataSourceFactory(client) { allowed })
        val challenge = ByteArray(64) { it.toByte() }
        val response = callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(challenge, ""))
        assertThat(response.data).isEqualTo(byteArrayOf(4, 5, 6))
        assertThat(requests.map { it.url.host }).containsExactly("license.example", "approved.example").inOrder()
        // The license POST still carries the Widevine challenge body, as the standard path does.
        requests.forEach { assertThat(it.method).isEqualTo("POST") }

        // Revoking the grant refuses the next open without reaching the network.
        allowed = emptyList()
        val refused =
            runCatching {
                callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(challenge, ""))
            }.exceptionOrNull()
        assertThat(refused).isNotNull()
        assertThat(requests).hasSize(2)
    }

    @Test
    fun `a certificate session refuses a license destination it was not granted`() {
        val item =
            androidx.media3.common.MediaItem
                .Builder()
                .setUri("music://candidate")
                .setDrmConfiguration(
                    androidx.media3.common.MediaItem.DrmConfiguration
                        .Builder(C.WIDEVINE_UUID)
                        .setLicenseUri("https://evil.example/license")
                        .setForceDefaultLicenseUri(true)
                        .build(),
                ).build()
        val reached = mutableListOf<String>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    reached += chain.request().url.host
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(ByteArray(0).toResponseBody())
                        .build()
                }.build()
        val callback = checkedLicenseCallback(item, pluginDrmDataSourceFactory(client) { listOf("gravity.eclipsemusic.app") })
        assertThat(
            runCatching {
                callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(1), ""))
            }.isFailure,
        ).isTrue()
        assertThat(reached).isEmpty()
    }

    @Test
    fun `redirect cannot send license headers or challenge to an ungranted destination`() {
        for (target in listOf("https://evil.example/license", "http://license.example/license")) {
            val reached = mutableListOf<String>()
            val client =
                OkHttpClient
                    .Builder()
                    .addInterceptor { chain ->
                        reached += chain.request().url.host
                        Response
                            .Builder()
                            .request(chain.request())
                            .protocol(Protocol.HTTP_1_1)
                            .code(307)
                            .message("fixture")
                            .header("Location", target)
                            .body(ByteArray(0).toResponseBody())
                            .build()
                    }.build()
            val callback =
                HttpMediaDrmCallback(
                    "https://license.example/license",
                    true,
                    pluginDrmDataSourceFactory(client) { listOf("license.example") },
                )
            callback.setKeyRequestProperty("Authorization", "secret")
            val failure =
                runCatching {
                    callback.executeKeyRequest(C.WIDEVINE_UUID, ExoMediaDrm.KeyRequest(byteArrayOf(1, 2, 3), ""))
                }.exceptionOrNull()
            assertThat(failure).isNotNull()
            assertThat(reached).containsExactly("license.example")
        }
    }
}
