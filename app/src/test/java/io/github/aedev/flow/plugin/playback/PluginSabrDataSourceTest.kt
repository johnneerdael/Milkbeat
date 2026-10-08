package io.github.aedev.flow.plugin.playback

import android.app.Application
import androidx.media3.datasource.DataSpec
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
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginSabrDataSourceTest {
    @Test
    fun `real HTTPS POST retains protocol headers and dedicated visitor cookie over accepted conflicts`() {
        val tls = loopbackTls()
        mockwebserver3.MockWebServer().use { server ->
            server.useHttps(tls.first.socketFactory)
            server.start()
            val client = OkHttpClient.Builder().sslSocketFactory(tls.first.socketFactory, tls.second).build()
            val accepted =
                mapOf(
                    "cookie" to "account=fake-accepted",
                    "content-TYPE" to "application/json",
                    "ACCEPT" to "text/html",
                    "User-Agent" to "accepted-client",
                    "Origin" to "https://accepted.example",
                    "Referer" to "https://accepted.example/tv",
                    "Authorization" to "fake-accepted-auth",
                    "Range" to "bytes=999-",
                )
            for (dedicatedVisitor in listOf(true, false)) {
                server.enqueue(
                    mockwebserver3.MockResponse
                        .Builder()
                        .body("fixture-envelope")
                        .build(),
                )
                val source = pluginSabrDataSourceFactory(client, accepted, {}, { listOf("localhost") }).createDataSource()
                val protocol =
                    mapOf(
                        "Content-Type" to "application/x-protobuf",
                        "Accept" to "application/vnd.yt-ump",
                        "user-agent" to "obsolete-client",
                        "origin" to "https://obsolete.example",
                        "referer" to "https://obsolete.example/tv",
                        "authorization" to "fake-obsolete-auth",
                        "range" to "bytes=10-",
                    ) + if (dedicatedVisitor) mapOf("Cookie" to "VISITOR_INFO1_LIVE=fake-dedicated") else emptyMap()
                source.open(
                    DataSpec
                        .Builder()
                        .setUri(server.url("/post").toString())
                        .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                        .setHttpBody(byteArrayOf(0, 1, -1))
                        .setHttpRequestHeaders(protocol)
                        .build(),
                )
                source.close()
                val wire = server.takeRequest()
                assertThat(wire.method).isEqualTo("POST")
                assertThat(wire.headers["Content-Type"]).isEqualTo("application/x-protobuf")
                assertThat(wire.headers["Accept"]).isEqualTo("application/vnd.yt-ump")
                assertThat(wire.headers["Cookie"]).isEqualTo(
                    if (dedicatedVisitor) "VISITOR_INFO1_LIVE=fake-dedicated" else "account=fake-accepted",
                )
                assertThat(wire.headers.values("Cookie")).hasSize(1)
                assertThat(wire.headers["User-Agent"]).isEqualTo("accepted-client")
                assertThat(wire.headers["Origin"]).isEqualTo("https://accepted.example")
                assertThat(wire.headers["Referer"]).isEqualTo("https://accepted.example/tv")
                assertThat(wire.headers["Authorization"]).isEqualTo("fake-accepted-auth")
                assertThat(wire.headers["Range"]).isNull()
            }
        }
    }

    @Test
    fun `raw POST preserves envelope and headers while discarding byte cache identity`() {
        val observed = mutableListOf<okhttp3.Request>()
        val bodies = mutableListOf<ByteArray>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    observed += chain.request()
                    val body = Buffer()
                    chain.request().body!!.writeTo(body)
                    bodies += body.readByteArray()
                    Response
                        .Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(byteArrayOf(0, 1, -1).toResponseBody())
                        .build()
                }.build()
        val source =
            pluginSabrDataSourceFactory(
                client,
                mapOf("Authorization" to "accepted"),
                {},
                { listOf("cdn.example") },
            ).createDataSource()
        val bytes = byteArrayOf(7, 0, -1)
        source.open(
            DataSpec
                .Builder()
                .setUri("https://cdn.example/videoplayback")
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(bytes)
                .setKey("original-song")
                .setHttpRequestHeaders(
                    mapOf(
                        "Authorization" to "obsolete",
                        "Cookie" to "visitor=fixture",
                    ),
                ).build(),
        )
        val output = ByteArray(3)
        assertThat(source.read(output, 0, output.size)).isEqualTo(3)
        source.close()
        assertThat(output).isEqualTo(byteArrayOf(0, 1, -1))
        assertThat(bodies.single()).isEqualTo(bytes)
        assertThat(observed.single().method).isEqualTo("POST")
        assertThat(observed.single().header("Authorization")).isEqualTo("accepted")
        assertThat(observed.single().header("Cookie")).isEqualTo("visitor=fixture")
        assertThat(observed.single().header("Range")).isNull()
    }

    @Test
    fun `physical byte offsets cannot resume a protocol POST after partial failure`() {
        var reached = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor {
                    reached++
                    throw IOException("Unexpected network")
                }.build()
        val source = pluginSabrDataSourceFactory(client, emptyMap(), {}, { listOf("cdn.example") }).createDataSource()
        val request =
            DataSpec
                .Builder()
                .setUri("https://cdn.example/post")
                .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                .setHttpBody(byteArrayOf(1, 2, 3))
                .setPosition(9)
                .build()
        assertThat(runCatching { source.open(request) }.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(reached).isEqualTo(0)
    }

    @Test
    fun `different accepted sources and approved CDN hops never share header state`() {
        val observed = mutableListOf<okhttp3.Request>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    observed += chain.request()
                    Response
                        .Builder()
                        .request(
                            chain.request(),
                        ).protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("fixture")
                        .body(byteArrayOf(1).toResponseBody())
                        .build()
                }.build()

        fun open(
            header: String,
            url: String,
        ) {
            val source =
                pluginSabrDataSourceFactory(
                    client,
                    mapOf("Authorization" to header),
                    {},
                    { listOf("*.example") },
                ).createDataSource()
            source.open(
                DataSpec
                    .Builder()
                    .setUri(url)
                    .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                    .setHttpBody(byteArrayOf(1))
                    .build(),
            )
            source.close()
        }
        open("recording-A", "https://first.example/videoplayback")
        open("recording-A", "https://redirect.example/videoplayback")
        open("recording-B", "https://redirect.example/videoplayback")
        assertThat(observed.map { it.header("Authorization") }).containsExactly("recording-A", "recording-A", "recording-B").inOrder()
    }

    @Test
    fun `real HTTPS redirect retains POST body and cookie only after checking the new grant`() {
        val tls = loopbackTls()
        mockwebserver3.MockWebServer().use { initial ->
            mockwebserver3.MockWebServer().use { redirected ->
                initial.useHttps(tls.first.socketFactory)
                redirected.useHttps(tls.first.socketFactory)
                initial.start()
                redirected.start()
                val destination =
                    redirected
                        .url("/post")
                        .newBuilder()
                        .host("127.0.0.1")
                        .build()
                initial.enqueue(
                    mockwebserver3.MockResponse
                        .Builder()
                        .code(307)
                        .addHeader("Location", destination)
                        .build(),
                )
                redirected.enqueue(
                    mockwebserver3.MockResponse
                        .Builder()
                        .body("native-envelope")
                        .build(),
                )
                val client = OkHttpClient.Builder().sslSocketFactory(tls.first.socketFactory, tls.second).build()
                val source =
                    pluginSabrDataSourceFactory(client, mapOf("Cookie" to "visitor=accepted"), {
                    }, { listOf("localhost", "127.0.0.1") }).createDataSource()
                val body = byteArrayOf(0, 1, -1)
                source.open(
                    DataSpec
                        .Builder()
                        .setUri(
                            initial.url("/post").toString(),
                        ).setHttpMethod(DataSpec.HTTP_METHOD_POST)
                        .setHttpBody(body)
                        .build(),
                )
                source.close()
                val first = initial.takeRequest()
                val next = redirected.takeRequest()
                assertThat(first.method).isEqualTo("POST")
                assertThat(next.method).isEqualTo("POST")
                assertThat(first.body!!.toByteArray()).isEqualTo(body)
                assertThat(next.body!!.toByteArray()).isEqualTo(body)
                assertThat(next.headers["Cookie"]).isEqualTo("visitor=accepted")
                initial.enqueue(
                    mockwebserver3.MockResponse
                        .Builder()
                        .code(307)
                        .addHeader("Location", destination)
                        .build(),
                )
                val denied =
                    pluginSabrDataSourceFactory(
                        client,
                        mapOf("Cookie" to "visitor=private"),
                        {},
                        { listOf("localhost") },
                    ).createDataSource()
                assertThat(
                    runCatching {
                        denied.open(
                            DataSpec
                                .Builder()
                                .setUri(
                                    initial.url("/post").toString(),
                                ).setHttpMethod(DataSpec.HTTP_METHOD_POST)
                                .setHttpBody(body)
                                .build(),
                        )
                    }.isFailure,
                ).isTrue()
                denied.close()
                assertThat(redirected.requestCount).isEqualTo(1)
            }
        }
    }

    @Test
    fun `interrupted Media3 loader cancels the pending OkHttp protocol POST`() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val call =
            java.util.concurrent.atomic
                .AtomicReference<okhttp3.Call>()
        val failure =
            java.util.concurrent.atomic
                .AtomicReference<Throwable>()
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    call.set(chain.call())
                    entered.countDown()
                    release.await()
                    throw IOException("Cancelled fixture")
                }.build()
        val source = pluginSabrDataSourceFactory(client, emptyMap(), {}, { listOf("cdn.example") }).createDataSource()
        val loader =
            Thread {
                try {
                    source.open(
                        DataSpec
                            .Builder()
                            .setUri(
                                "https://cdn.example/post",
                            ).setHttpMethod(DataSpec.HTTP_METHOD_POST)
                            .setHttpBody(byteArrayOf(1))
                            .build(),
                    )
                } catch (
                    error: Throwable,
                ) {
                    failure.set(error)
                }
            }
        try {
            loader.start()
            assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue()
            loader.interrupt()
            loader.join(2000)
            assertThat(loader.isAlive).isFalse()
            assertThat(call.get().isCanceled()).isTrue()
            assertThat(failure.get()).isNotNull()
        } finally {
            release.countDown()
            source.close()
        }
    }

    // A synthetic loopback-only PKCS12 fixture; JCA/JSSE owns certificates and TLS.
    private fun loopbackTls(): Pair<javax.net.ssl.SSLContext, javax.net.ssl.X509TrustManager> {
        val store = java.security.KeyStore.getInstance("PKCS12")
        javaClass.getResourceAsStream("/plugin-sabr-loopback.p12").use { store.load(it, "fixture-only".toCharArray()) }
        val keys =
            javax.net.ssl.KeyManagerFactory
                .getInstance(
                    javax.net.ssl.KeyManagerFactory
                        .getDefaultAlgorithm(),
                )
        keys.init(store, "fixture-only".toCharArray())
        val trusts =
            javax.net.ssl.TrustManagerFactory
                .getInstance(
                    javax.net.ssl.TrustManagerFactory
                        .getDefaultAlgorithm(),
                )
        trusts.init(store)
        val trust = trusts.trustManagers.filterIsInstance<javax.net.ssl.X509TrustManager>().single()
        val context =
            javax.net.ssl.SSLContext
                .getInstance("TLS")
        context.init(keys.keyManagers, arrayOf(trust), null)
        return context to trust
    }

    @Test
    fun `revoked account grant and foreign redirect fail before a request and redact credentials`() {
        var reached = 0
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor {
                    reached++
                    throw IOException("Unexpected network")
                }.build()
        for (target in listOf("https://evil.example/post?token=secret", "http://cdn.example/post?token=secret")) {
            val source = pluginSabrDataSourceFactory(client, mapOf("Cookie" to "secret"), {}, { listOf("cdn.example") }).createDataSource()
            val failure =
                runCatching {
                    source.open(
                        DataSpec
                            .Builder()
                            .setUri(target)
                            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
                            .setHttpBody(byteArrayOf(1))
                            .build(),
                    )
                }.exceptionOrNull()
            assertThat(failure).isNotNull()
            assertThat(failure!!.message).doesNotContain("secret")
        }
        val revoked =
            pluginSabrDataSourceFactory(client, emptyMap(), {
                throw IOException("Account changed")
            }, { listOf("cdn.example") }).createDataSource()
        assertThat(
            runCatching {
                revoked.open(
                    DataSpec
                        .Builder()
                        .setUri(
                            "https://cdn.example/post",
                        ).setHttpMethod(DataSpec.HTTP_METHOD_POST)
                        .setHttpBody(byteArrayOf(1))
                        .build(),
                )
            }.isFailure,
        ).isTrue()
        assertThat(reached).isEqualTo(0)
    }
}
