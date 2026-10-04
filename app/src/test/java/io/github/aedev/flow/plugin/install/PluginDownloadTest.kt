package io.github.aedev.flow.plugin.install

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

class PluginDownloadTest {
    private val page = "https://buzzheavier.com/abcdef123456"
    private val bytes = "signed package bytes".toByteArray()

    @Test
    fun `Buzzheavier resolves the signed token and HX redirect before downloading`() =
        runBlocking {
            val requests = mutableListOf<Request>()
            val client =
                client { request ->
                    requests += request
                    when (request.url.toString()) {
                        page -> response(request, "<a hx-get='/abcdef123456/download?t=token&amp;mirror=1'>Download</a>")
                        "$page/download?t=token&mirror=1" -> response(request, "", "HX-Redirect" to "https://ts.buzzheavier.com/d/file")
                        "https://ts.buzzheavier.com/d/file" -> response(request, bytes)
                        else -> error("Unexpected download request")
                    }
                }

            assertThat(downloadPlugin(client, page)).isEqualTo(bytes)
            assertThat(requests).hasSize(3)
            assertThat(requests[1].header("HX-Request")).isEqualTo("true")
            assertThat(requests[1].header("Referer")).isEqualTo(page)
            assertThat(requests[1].header("HX-Current-URL")).isEqualTo(page)
        }

    @Test
    fun `a challenge that clears on a later try still downloads natively`() =
        runBlocking {
            var landings = 0
            val client =
                client { request ->
                    when (request.url.toString()) {
                        page -> {
                            if (++landings == 1) {
                                response(
                                    request,
                                    "<title>Just a moment...</title>",
                                    "cf-mitigated" to "challenge",
                                ).newBuilder().code(403).build()
                            } else {
                                response(request, "<a hx-get='/abcdef123456/download?t=token'>Download</a>")
                            }
                        }

                        "$page/download?t=token" -> {
                            response(request, "", "HX-Redirect" to "https://ts.buzzheavier.com/d/file")
                        }

                        else -> {
                            response(request, bytes)
                        }
                    }
                }

            assertThat(downloadPlugin(client, page, Duration.ZERO)).isEqualTo(bytes)
            assertThat(landings).isEqualTo(2)
        }

    @Test
    fun `a challenge that never clears hands the file page to a browser`() =
        runBlocking {
            var landings = 0
            val client =
                client { request ->
                    landings++
                    response(request, "<html><head><title>Just a moment...</title></head></html>")
                }

            val failure = runCatching { downloadPlugin(client, "https://bzzhr.to/abcdef123456?ref=x", Duration.ZERO) }.exceptionOrNull()
            assertThat((failure as BuzzheavierChallengeException).page).isEqualTo(page)
            assertThat(failure.messageResource).isEqualTo(R.string.tv_plugins_download_verification)
            assertThat(landings).isEqualTo(3)
        }

    @Test
    fun `a file link from the browser downloads only from Buzzheavier over HTTPS`() =
        runBlocking {
            val client = client { request -> response(request, bytes) }
            assertThat(downloadBuzzheavierFile(client, page, "https://ts.buzzheavier.com/d/abcdef123456?v=signed")).isEqualTo(bytes)
            listOf(
                "https://evil.example/d/abcdef123456",
                "http://ts.buzzheavier.com/d/abcdef123456",
                "https://ts.buzzheavier.com:8443/d/abcdef123456",
                "https://ts.buzzheavier.com.evil.example/d/abcdef123456",
                "https://ts.buzzheavier.com/d/otherfile99",
            ).forEach { target ->
                assertThat(runCatching { downloadBuzzheavierFile(client, page, target) }.exceptionOrNull())
                    .isInstanceOf(PluginInstallException::class.java)
            }
        }

    @Test
    fun `only Buzzheavier's own download links count as the file`() {
        assertThat(isBuzzheavierFileUrl("https://ts.buzzheavier.com/d/abcdef123456?v=signed", page)).isTrue()
        listOf(
            page,
            "https://buzzheavier.com/d/abcdef123456",
            "http://ts.buzzheavier.com/d/abcdef123456",
            "https://ts.buzzheavier.com/abcdef123456",
            "https://ts.buzzheavier.com.evil.example/d/abcdef123456",
            "https://user@ts.buzzheavier.com/d/abcdef123456",
            "https://ts.buzzheavier.com/d/otherfile99",
        ).forEach { assertThat(isBuzzheavierFileUrl(it, page)).isFalse() }
    }

    @Test
    fun `a challenge on the download request also hands the page to a browser`() =
        runBlocking {
            val client =
                client { request ->
                    if (request.url.toString() == page) {
                        response(request, "<a hx-get='/abcdef123456/download?t=token'>Download</a>")
                    } else {
                        response(request, "", "cf-mitigated" to "challenge").newBuilder().code(403).build()
                    }
                }

            val failure = runCatching { downloadPlugin(client, page, Duration.ZERO) }.exceptionOrNull()
            assertThat((failure as BuzzheavierChallengeException).page).isEqualTo(page)
        }

    @Test
    fun `a removed or forbidden file fails at once instead of asking for a browser`() =
        runBlocking {
            listOf(403, 404).forEach { status ->
                var landings = 0
                val client =
                    client { request ->
                        landings++
                        response(request, "<html>Gone</html>").newBuilder().code(status).build()
                    }
                val failure = runCatching { downloadPlugin(client, page, Duration.ZERO) }.exceptionOrNull()
                assertThat((failure as PluginInstallException).messageResource).isEqualTo(R.string.tv_plugins_download_failed)
                assertThat(failure).isNotInstanceOf(BuzzheavierChallengeException::class.java)
                assertThat(landings).isEqualTo(1)
            }
        }

    @Test
    fun `direct package URLs still download without a landing page`() =
        runBlocking {
            val client = client { request -> response(request, bytes) }
            assertThat(downloadPlugin(client, "https://example.com/demo.mbplugin")).isEqualTo(bytes)
        }

    @Test
    fun `a forbidden direct URL does not report a Buzzheavier challenge`() =
        runBlocking {
            val client = client { request -> response(request, "Denied").newBuilder().code(403).build() }
            val failure = runCatching { downloadPlugin(client, "https://example.com/demo.mbplugin") }.exceptionOrNull()
            assertThat((failure as PluginInstallException).messageResource).isEqualTo(R.string.tv_plugins_download_failed)
        }

    @Test
    fun `an oversized page with no declared length is refused while reading`() =
        runBlocking {
            val body =
                object : ResponseBody() {
                    private val buffer = Buffer().write(ByteArray(1024 * 1024 + 1))

                    override fun contentType() = "text/html".toMediaType()

                    override fun contentLength() = -1L

                    override fun source() = buffer
                }
            val client = client { request -> response(request, bytes).newBuilder().body(body).build() }
            val failure = runCatching { downloadPlugin(client, page, Duration.ZERO) }.exceptionOrNull()
            assertThat((failure as PluginInstallException).messageResource).isEqualTo(R.string.tv_plugins_download_page_large)
        }

    @Test
    fun `Buzzheavier never sends a request to an unrelated token host`() =
        runBlocking {
            val client = client { request -> response(request, "<a hx-get='https://evil.example/abcdef123456/download?t=x'>Download</a>") }
            assertThat(
                runCatching { downloadPlugin(client, page, Duration.ZERO) }.exceptionOrNull(),
            ).isInstanceOf(PluginInstallException::class.java)
        }

    @Test
    fun `Buzzheavier refuses external and insecure CDN redirects`() =
        runBlocking {
            listOf("https://evil.example/plugin", "http://ts.buzzheavier.com/d/file", "https://buzzheavier.com.evil.example/file")
                .forEach { target ->
                    var requests = 0
                    val client =
                        client { request ->
                            requests++
                            if (request.url.toString() == page) {
                                response(request, "<a hx-get='/abcdef123456/download?t=token'>Download</a>")
                            } else {
                                response(request, "", "HX-Redirect" to target)
                            }
                        }
                    assertThat(
                        runCatching { downloadPlugin(client, page, Duration.ZERO) }.exceptionOrNull(),
                    ).isInstanceOf(PluginInstallException::class.java)
                    assertThat(requests).isEqualTo(2)
                }
        }

    @Test
    fun `a missing token or failed landing page is not treated as a package`() =
        runBlocking {
            listOf(200, 403, 404).forEach { status ->
                val client = client { request -> response(request, "<html>Unavailable</html>").newBuilder().code(status).build() }
                assertThat(
                    runCatching { downloadPlugin(client, page, Duration.ZERO) }.exceptionOrNull(),
                ).isInstanceOf(PluginInstallException::class.java)
            }
        }

    @Test
    fun `redirect loops terminate instead of making unbounded requests`() =
        runBlocking {
            var requests = 0
            val client =
                client { request ->
                    requests++
                    response(request, "", "Location" to request.url.toString()).newBuilder().code(302).build()
                }
            assertThat(runCatching { downloadPlugin(client, "https://example.com/demo.mbplugin") }.exceptionOrNull())
                .isInstanceOf(PluginInstallException::class.java)
            assertThat(requests).isAtMost(6)
        }

    @Test
    fun `oversized packages are refused before reading their body`() =
        runBlocking {
            val body =
                object : ResponseBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()

                    override fun contentLength() = 65L * 1024 * 1024

                    override fun source() = Buffer()
                }
            val client = client { request -> response(request, bytes).newBuilder().body(body).build() }
            assertThat(runCatching { downloadPlugin(client, "https://example.com/demo.mbplugin") }.exceptionOrNull())
                .isInstanceOf(PluginInstallException::class.java)
        }

    @Test
    fun `cancelling a download releases its caller without waiting for the network`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val client =
                client { request ->
                    started.complete(Unit)
                    release.await(5, TimeUnit.SECONDS)
                    response(request, bytes)
                }
            try {
                val job = launch { downloadPlugin(client, "https://example.com/demo.mbplugin") }
                withTimeout(1_000) { started.await() }
                withTimeout(1_000) { job.cancelAndJoin() }
                assertThat(job.isCancelled).isTrue()
            } finally {
                release.countDown()
                client.dispatcher.executorService.shutdown()
            }
        }

    private fun client(answer: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain -> answer(chain.request()) }.build()

    private fun response(
        request: Request,
        text: String,
        vararg headers: Pair<String, String>,
    ) = response(request, text.toByteArray(), *headers)

    private fun response(
        request: Request,
        bytes: ByteArray,
        vararg headers: Pair<String, String>,
    ): Response =
        Response
            .Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody("application/octet-stream".toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
}
