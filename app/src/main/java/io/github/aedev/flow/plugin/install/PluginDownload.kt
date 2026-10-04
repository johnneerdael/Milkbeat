package io.github.aedev.flow.plugin.install

import io.github.aedev.flow.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private const val MAX_PLUGIN_BYTES = 64L * 1024 * 1024
private const val MAX_PAGE_BYTES = 1024L * 1024
private const val MAX_REDIRECTS = 5
private const val DOWNLOAD_TIMEOUT_SECONDS = 30L
private const val BUZZHEAVIER_HOST = "buzzheavier.com"
private const val BUZZHEAVIER_ATTEMPTS = 3
private val BUZZHEAVIER_LINK_HOSTS = setOf(BUZZHEAVIER_HOST, "www.buzzheavier.com", "bzzhr.to")
private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36"

/** Buzzheavier kept answering [page] with a browser challenge; a real browser can pass it there. */
internal class BuzzheavierChallengeException(
    val page: String,
) : PluginInstallException(messageResource = R.string.tv_plugins_download_verification)

internal suspend fun downloadPlugin(
    client: OkHttpClient,
    value: String,
    challengeBackoff: Duration = 1.seconds,
): ByteArray =
    withContext(Dispatchers.IO) {
        val url = pluginUrl(value) ?: throw PluginInstallException(messageResource = R.string.tv_plugins_input_invalid)
        val buzzheavier = url.host in BUZZHEAVIER_LINK_HOSTS
        val session = downloadSession(client, cookies = buzzheavier)
        val downloadUrl = if (buzzheavier) resolveBuzzheavier(session, url, challengeBackoff) else url
        followDownload(session, downloadUrl, MAX_PLUGIN_BYTES, buzzheavier).bytes
    }

/** Downloads the link to [page]'s file that a browser was handed after passing Buzzheavier's challenge. */
internal suspend fun downloadBuzzheavierFile(
    client: OkHttpClient,
    page: String,
    fileUrl: String,
): ByteArray =
    withContext(Dispatchers.IO) {
        if (!isBuzzheavierFileUrl(fileUrl, page)) throw PluginInstallException(messageResource = R.string.tv_plugins_download_redirect)
        followDownload(downloadSession(client, cookies = false), fileUrl.toHttpUrl(), MAX_PLUGIN_BYTES, true).bytes
    }

/** The file page a Buzzheavier link points at, or null when [value] is no Buzzheavier file link. */
internal fun buzzheavierPage(value: String): HttpUrl? {
    val url = pluginUrl(value)?.takeIf { it.host in BUZZHEAVIER_LINK_HOSTS } ?: return null
    val id = url.pathSegments.filter { it.isNotEmpty() }.singleOrNull()
    if (id == null || !id.matches(Regex("[A-Za-z0-9]{8,16}"))) return null
    return url
        .newBuilder()
        .scheme("https")
        .host(BUZZHEAVIER_HOST)
        .port(443)
        .query(null)
        .fragment(null)
        .build()
}

/** Whether [url] is the download link Buzzheavier's file [page] hands a browser for that same file. */
internal fun isBuzzheavierFileUrl(
    url: String,
    page: String,
): Boolean {
    val id = buzzheavierPage(page)?.pathSegments?.first() ?: return false
    val file = runCatching { secureBuzzheavierUrl("https://$BUZZHEAVIER_HOST/".toHttpUrl(), url) }.getOrNull() ?: return false
    return file.host != BUZZHEAVIER_HOST && file.pathSegments == listOf("d", id)
}

private fun downloadSession(
    client: OkHttpClient,
    cookies: Boolean,
): OkHttpClient =
    client
        .newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(DOWNLOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .apply { if (cookies) cookieJar(DownloadCookies()) }
        .build()

private suspend fun resolveBuzzheavier(
    client: OkHttpClient,
    source: HttpUrl,
    challengeBackoff: Duration,
): HttpUrl {
    val page = buzzheavierPage(source.toString()) ?: throw PluginInstallException(messageResource = R.string.tv_plugins_input_invalid)
    val id = page.pathSegments.first()
    val document = landingPage(client, page, challengeBackoff)
    val trigger =
        document
            .select("[hx-get]")
            .mapNotNull { element -> page.resolve(element.attr("hx-get")) }
            .firstOrNull {
                it.scheme == page.scheme && it.host == page.host && it.port == page.port &&
                    it.encodedPath == "/$id/download" && !it.queryParameter("t").isNullOrBlank() &&
                    it.username.isEmpty() && it.password.isEmpty()
            } ?: throw PluginInstallException(messageResource = R.string.tv_plugins_download_missing)
    val response =
        request(
            client,
            downloadRequest(trigger)
                .header("HX-Request", "true")
                .header("HX-Current-URL", page.toString())
                .header("Referer", page.toString())
                .build(),
            MAX_PAGE_BYTES,
        )
    if (response.isChallenge) throw BuzzheavierChallengeException(page.toString())
    checkStatus(response, allowRedirect = true, buzzheavier = true)
    val target = response.headers["HX-Redirect"] ?: response.headers["Location"]
    return secureBuzzheavierUrl(trigger, target)
}

// Cloudflare decides per request, so a challenge can clear on a later try; one that never does
// leaves the page to a browser.
private suspend fun landingPage(
    client: OkHttpClient,
    page: HttpUrl,
    challengeBackoff: Duration,
): Document {
    repeat(BUZZHEAVIER_ATTEMPTS) { attempt ->
        if (attempt > 0) delay(challengeBackoff * (1 shl (attempt - 1)))
        val document =
            try {
                Jsoup.parse(
                    followDownload(client, page, MAX_PAGE_BYTES, secureBuzzheavier = true).bytes.toString(Charsets.UTF_8),
                    page.toString(),
                )
            } catch (e: PluginInstallException) {
                if (e.messageResource != R.string.tv_plugins_download_verification) throw e
                null
            }
        if (document != null && !document.title().contains("Just a moment", ignoreCase = true)) return document
    }
    throw BuzzheavierChallengeException(page.toString())
}

private suspend fun followDownload(
    client: OkHttpClient,
    initial: HttpUrl,
    limit: Long,
    secureBuzzheavier: Boolean,
): DownloadResponse {
    var url = initial
    repeat(MAX_REDIRECTS + 1) { attempt ->
        val response = request(client, downloadRequest(url).build(), limit)
        checkStatus(response, allowRedirect = true, buzzheavier = secureBuzzheavier)
        if (response.code !in 300..399) return response
        if (attempt == MAX_REDIRECTS) throw PluginInstallException(messageResource = R.string.tv_plugins_download_redirect)
        val target = response.headers["Location"]
        url =
            if (secureBuzzheavier) {
                secureBuzzheavierUrl(url, target)
            } else {
                target?.let { url.resolve(it) }?.takeIf { it.username.isEmpty() && it.password.isEmpty() }
                    ?: throw PluginInstallException(messageResource = R.string.tv_plugins_download_redirect)
            }
    }
    throw PluginInstallException(messageResource = R.string.tv_plugins_download_redirect)
}

private fun downloadRequest(url: HttpUrl): Request.Builder =
    Request
        .Builder()
        .url(url)
        .cacheControl(CacheControl.FORCE_NETWORK)
        .header("User-Agent", USER_AGENT)

private fun secureBuzzheavierUrl(
    base: HttpUrl,
    target: String?,
): HttpUrl =
    target
        ?.let { base.resolve(it) }
        ?.takeIf {
            it.scheme == "https" && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
                (it.host == BUZZHEAVIER_HOST || it.host.endsWith(".$BUZZHEAVIER_HOST"))
        } ?: throw PluginInstallException(messageResource = R.string.tv_plugins_download_redirect)

private data class DownloadResponse(
    val code: Int,
    val headers: Headers,
    val bytes: ByteArray,
) {
    val isChallenge: Boolean get() = headers["cf-mitigated"] == "challenge"
}

private fun checkStatus(
    response: DownloadResponse,
    allowRedirect: Boolean,
    buzzheavier: Boolean,
) {
    if (buzzheavier && response.isChallenge) {
        throw PluginInstallException(messageResource = R.string.tv_plugins_download_verification)
    }
    if (response.code !in 200..299 && !(allowRedirect && response.code in 300..399)) {
        throw PluginInstallException(messageResource = R.string.tv_plugins_download_failed)
    }
}

private suspend fun request(
    client: OkHttpClient,
    request: Request,
    limit: Long,
): DownloadResponse =
    suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            PluginInstallException(cause = e, messageResource = R.string.tv_plugins_download_failed),
                        )
                    }
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        val result =
                            response.use {
                                val bytes =
                                    if (it.isSuccessful && it.header("HX-Redirect") == null) {
                                        val source = it.body.source()
                                        if (it.body.contentLength() > limit || source.request(limit + 1)) {
                                            throw PluginInstallException(
                                                messageResource =
                                                    if (limit == MAX_PAGE_BYTES) {
                                                        R.string.tv_plugins_download_page_large
                                                    } else {
                                                        R.string.tv_plugins_download_large
                                                    },
                                            )
                                        }
                                        source.buffer.readByteArray()
                                    } else {
                                        ByteArray(0)
                                    }
                                DownloadResponse(it.code, it.headers, bytes)
                            }
                        continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(
                                if (e is PluginInstallException) {
                                    e
                                } else {
                                    PluginInstallException(
                                        cause = e,
                                        messageResource = R.string.tv_plugins_download_failed,
                                    )
                                },
                            )
                        }
                    }
                }
            },
        )
    }

private class DownloadCookies : CookieJar {
    private var cookies = emptyList<Cookie>()

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val replaced = cookies.map { Triple(it.name, it.domain, it.path) }.toSet()
        this.cookies = this.cookies.filterNot { Triple(it.name, it.domain, it.path) in replaced } + cookies
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies = cookies.filter { it.expiresAt > System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
}
