package io.github.aedev.flow.plugin.host

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.AudioProviderAttempt
import io.github.aedev.flow.plugin.playback.PluginAudioFixture
import io.github.aedev.flow.plugin.playback.PluginSabrDataSourceTest
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import io.github.aedev.flow.plugin.playback.resolveAudioAttempts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.HttpRequest
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class PluginHttpCancellationTest : PluginAudioFixture() {
    @Test
    fun `higher-ranked playable provider cancels a losing real HTTPS response before it sends headers`() =
        runBlocking {
            withServer { server, client, slowCall, _, failed ->
                val blocked = CompletableDeferred<Unit>()
                server.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (request.url.encodedPath == "/slow") {
                                blocked.complete(Unit)
                                MockResponse
                                    .Builder()
                                    .body("slow")
                                    .headersDelay(5, TimeUnit.SECONDS)
                                    .build()
                            } else {
                                MockResponse.Builder().body("fast").build()
                            }
                    }
                val fast = plugin.copy(manifest = plugin.manifest.copy(id = "fast"))
                val slow = plugin.copy(manifest = plugin.manifest.copy(id = "slow"))
                val http = PluginHttp(client, listOf("localhost"))
                try {
                    val winner =
                        withTimeout(2000) {
                            resolveAudioAttempts(
                                listOf(AudioProviderAttempt(fast, null), AudioProviderAttempt(slow, null)),
                                concurrent = true,
                            ) { provider ->
                                if (provider.plugin.id == "fast") blocked.await()
                                val response = http.fetch(HttpRequest(server.url("/${provider.plugin.id}").toString(), timeoutMs = 4000))
                                ResolvedAudio(
                                    provider.plugin.id,
                                    candidate,
                                    AudioStream(response.url, "recording", "audio", "audio/mp4"),
                                    Long.MAX_VALUE,
                                    false,
                                )
                            }
                        }
                    assertThat(winner.audio?.pluginId).isEqualTo("fast")
                    assertThat(slowCall.get().isCanceled()).isTrue()
                    withTimeout(2000) { failed.await() }
                } finally {
                    slowCall.get()?.cancel()
                }
            }
        }

    @Test
    fun `cancelling a real HTTPS response also cancels its blocked body read`() =
        runBlocking {
            withServer { server, client, slowCall, bodyStarted, failed ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .body("delayed body")
                        .throttleBody(1, 5, TimeUnit.SECONDS)
                        .build(),
                )
                val fetch =
                    async { PluginHttp(client, listOf("localhost")).fetch(HttpRequest(server.url("/slow").toString(), timeoutMs = 4000)) }
                try {
                    withTimeout(2000) { bodyStarted.await() }
                    withTimeout(2000) { fetch.cancelAndJoin() }
                    assertThat(fetch.isCancelled).isTrue()
                    assertThat(slowCall.get().isCanceled()).isTrue()
                    withTimeout(2000) { failed.await() }
                } finally {
                    slowCall.get()?.cancel()
                    fetch.cancelAndJoin()
                }
            }
        }

    @Test
    fun `normal HTTPS response releases its body and preserves status headers and text`() =
        runBlocking {
            withServer { server, client, _, _, _ ->
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(201)
                        .addHeader("X-Result", "accepted")
                        .body("complete body")
                        .build(),
                )
                val finished = CompletableDeferred<Unit>()
                val observed =
                    client
                        .newBuilder()
                        .eventListener(
                            object : EventListener() {
                                override fun callEnd(call: Call) {
                                    finished.complete(Unit)
                                }
                            },
                        ).build()
                val response = PluginHttp(observed, listOf("localhost")).fetch(HttpRequest(server.url("/normal").toString()))
                assertThat(response.status).isEqualTo(201)
                assertThat(response.headers["x-result"]).isEqualTo("accepted")
                assertThat(response.body).isEqualTo("complete body")
                withTimeout(2000) { finished.await() }
            }
        }

    private suspend fun withServer(
        body: suspend (MockWebServer, OkHttpClient, AtomicReference<Call>, CompletableDeferred<Unit>, CompletableDeferred<Unit>) -> Unit,
    ) {
        val tls = PluginSabrDataSourceTest().loopbackTls()
        MockWebServer().use { server ->
            server.useHttps(tls.first.socketFactory)
            server.start()
            val call = AtomicReference<Call>()
            val bodyStarted = CompletableDeferred<Unit>()
            val failed = CompletableDeferred<Unit>()
            val client =
                OkHttpClient
                    .Builder()
                    .sslSocketFactory(tls.first.socketFactory, tls.second)
                    .eventListener(
                        object : EventListener() {
                            override fun callStart(started: Call) {
                                if (started.request().url.encodedPath == "/slow") call.set(started)
                            }

                            override fun responseBodyStart(started: Call) {
                                if (started.request().url.encodedPath == "/slow") bodyStarted.complete(Unit)
                            }

                            override fun callFailed(
                                ended: Call,
                                ioe: IOException,
                            ) {
                                if (ended.request().url.encodedPath == "/slow") failed.complete(Unit)
                            }
                        },
                    ).build()
            try {
                body(server, client, call, bodyStarted, failed)
            } finally {
                call.get()?.cancel()
                client.dispatcher.executorService.shutdown()
                client.connectionPool.evictAll()
            }
        }
    }
}
