package io.github.aedev.flow.plugin.host

import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.player.diagnostics.TraceEvent
import io.github.aedev.flow.player.diagnostics.TraceField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.plugin.ConnectionInspectRequest
import nl.neerdael.milkbeat.plugin.ConnectionManifest
import nl.neerdael.milkbeat.plugin.HttpBodyEncoding
import nl.neerdael.milkbeat.plugin.HttpRequest
import nl.neerdael.milkbeat.plugin.HttpResponse
import nl.neerdael.milkbeat.plugin.PluginJson
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val DEFAULT_TIMEOUT_MS = 20_000L
private const val MAX_TIMEOUT_MS = 60_000L
private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
private const val MAX_MANIFEST_BYTES = 2L * 1024 * 1024
private val BODYLESS_METHODS = setOf("GET", "HEAD")

private object ReviewedManifestRequest

class PluginHttpException(
    message: String,
) : IOException(message)

/**
 * `mb.http.fetch` for one plugin: the app's HTTP client, restricted to the hosts the listener granted.
 * The check runs on every hop, so a redirect cannot lead a plugin anywhere it was not allowed to go.
 * [allowedHosts] is read per hop, so an approval or revocation that just changed takes effect on the
 * next request. Plugins handle their own cookies through headers; nothing is shared between plugins.
 */
internal class PluginHttp(
    base: OkHttpClient,
    private val allowedHosts: () -> List<String>,
    /** Public-destination policy; a test may pass the system resolver for its local server. */
    dns: Dns = publicDns(),
    private val reviewedManifest: () -> HttpUrl? = { null },
) {
    private val client =
        base
            .newBuilder()
            .publicPluginDestination(dns)
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .addNetworkInterceptor(Interceptor { chain -> chain.proceed(checked(chain.request())) })
            .build()

    suspend fun fetch(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val method = request.method.uppercase()
            val url = request.url.toHttpUrlOrNull() ?: throw PluginHttpException("Invalid plugin destination")
            val confirming = !httpsDestinationAllowed(url, allowedHosts()) && url == reviewedManifest()
            if (confirming &&
                (method != "GET" || request.body != null || request.headers.keys.any { !it.equals("accept", ignoreCase = true) })
            ) {
                throw PluginHttpException("Manifest confirmation requires a credential-free GET")
            }
            val body =
                request.body
                    ?.takeUnless { method in BODYLESS_METHODS }
                    ?.let { data ->
                        if (request.bodyEncoding ==
                            HttpBodyEncoding.BASE64
                        ) {
                            Base64.getDecoder().decode(data)
                        } else {
                            data.toByteArray(Charsets.UTF_8)
                        }
                    }?.toRequestBody(
                        request.headers.entries
                            .firstOrNull { it.key.equals("content-type", ignoreCase = true) }
                            ?.value
                            ?.toMediaTypeOrNull(),
                    )
            val call =
                client
                    .newBuilder()
                    .apply { if (confirming) cache(null) }
                    .followRedirects(request.followRedirects && !confirming)
                    .followSslRedirects(request.followRedirects && !confirming)
                    .callTimeout((request.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceAtMost(MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(
                        checked(
                            Request
                                .Builder()
                                .url(url)
                                .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                                .apply { if (confirming) tag(ReviewedManifestRequest::class.java, ReviewedManifestRequest) }
                                .method(method, body ?: if (method in BODYLESS_METHODS) null else ByteArray(0).toRequestBody())
                                .build(),
                        ),
                    )
            val trace = PlaybackTrace.start(TraceEvent.HTTP_STARTED, pluginHttpTraceCategory(url))
            var status = 0L
            var size = 0L
            var success = false
            try {
                call.readResponse { response ->
                    status = response.code.toLong()
                    val length = response.body.contentLength()
                    if (length > MAX_RESPONSE_BYTES) throw PluginHttpException("Response of $length bytes is too large")
                    val source = response.body.source()
                    if (source.request(MAX_RESPONSE_BYTES + 1)) throw PluginHttpException("Response is too large")
                    val bytes = source.buffer.readByteArray()
                    size = bytes.size.toLong()
                    val responseText =
                        if (request.responseEncoding == HttpBodyEncoding.BASE64) {
                            Base64.getEncoder().encodeToString(bytes)
                        } else {
                            bytes.toString(Charsets.UTF_8)
                        }
                    HttpResponse(
                        status = response.code,
                        url = response.request.url.toString(),
                        headers =
                            response.headers.names().associate { name ->
                                val lower = name.lowercase()
                                lower to response.headers.values(name).joinToString(if (lower == "set-cookie") "\n" else ", ")
                            },
                        body = responseText,
                    ).also { success = true }
                }
            } finally {
                trace.event(
                    TraceEvent.HTTP_FINISHED,
                    TraceField.STATUS to status,
                    TraceField.BYTES to size,
                    TraceField.SUCCESS to if (success) 1L else 0L,
                )
            }
        }

    private fun checked(request: Request): Request {
        val url = request.url
        if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
            throw PluginHttpException("Plugins require an HTTPS destination without user information or fragment")
        }
        val confirming = request.tag(ReviewedManifestRequest::class.java) != null && url == reviewedManifest()
        if (!confirming && !httpsDestinationAllowed(url, allowedHosts())) {
            throw PluginHttpException("${url.host} is not in the plugin's permissions")
        }
        return request
    }
}

// Keep cancellation attached while consuming the body; a header-only await detaches too early.
private suspend fun <T> Call.readResponse(read: (Response) -> T): T =
    suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: IOException,
                ) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    try {
                        val result =
                            response.use {
                                if (!continuation.isActive) return
                                read(it)
                            }
                        continuation.resume(result)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            },
        )
    }

/**
 * `connections.inspect`: the host fetches a candidate connection's manifest itself, with no plugin
 * credentials and no redirects, so an origin the listener has not approved yet can still be
 * reviewed. Only public HTTPS destinations with no user information qualify; the body is bounded.
 */
internal suspend fun inspectConnectionManifest(
    base: OkHttpClient,
    request: ConnectionInspectRequest,
): ConnectionManifest =
    withContext(Dispatchers.IO) {
        val url = normalizedManifestUrl(request.url)
        val body =
            base
                .newBuilder()
                .publicPluginDestination()
                .cookieJar(okhttp3.CookieJar.NO_COOKIES)
                .cache(null)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .header("Accept", "application/json")
                        .build(),
                ).readResponse { response ->
                    if (response.code !in 200..299) throw PluginHttpException("The add-on answered HTTP ${response.code}")
                    if (response.body.contentLength() > MAX_MANIFEST_BYTES) throw PluginHttpException("The add-on manifest is too large")
                    val source = response.body.source()
                    if (source.request(MAX_MANIFEST_BYTES + 1)) throw PluginHttpException("The add-on manifest is too large")
                    source.buffer.readUtf8()
                }
        val payload =
            runCatching { PluginJson.parseToJsonElement(body) }.getOrNull()?.let { manifestFields(it, url.toString()) }
        payload ?: throw PluginHttpException("The add-on did not return a manifest")
    }

/** The install link's manifest address (A01): a trailing `manifest.json`, exactly once. */
internal fun normalizedManifestUrl(input: String): okhttp3.HttpUrl {
    if (input.length > 8192) throw PluginHttpException("A connection link is too long")
    val trimmed = input.trim()
    val base =
        trimmed
            .toHttpUrlOrNull()
            ?.takeIf { it.isHttps }
            ?: throw PluginHttpException("A connection link must be an HTTPS URL")
    if (base.username.isNotEmpty() || base.password.isNotEmpty() || base.fragment != null) {
        throw PluginHttpException("A connection link carries no user information or fragment")
    }
    if (isPrivateConnectionHost(base.host)) throw PluginHttpException("A connection link must name a public destination")
    val segments = base.pathSegments.filter { it.isNotEmpty() }
    if (segments.size > 12 || segments.any { part -> part.any { char -> char.isISOControl() || char == '/' || char == '\\' } }) {
        throw PluginHttpException("A connection link's path is not a manifest address")
    }
    val stem = segments.dropLastWhile { it.equals("manifest.json", ignoreCase = true) }
    return base
        .newBuilder()
        .encodedPath("/")
        .apply { stem.forEach(::addPathSegment) }
        .addPathSegment("manifest.json")
        .build()
}

private fun manifestFields(
    payload: kotlinx.serialization.json.JsonElement,
    url: String,
): ConnectionManifest? {
    val obj = payload as? kotlinx.serialization.json.JsonObject ?: return null

    fun primitive(value: kotlinx.serialization.json.JsonElement?): String? =
        (value as? kotlinx.serialization.json.JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.trim()
            ?.ifEmpty { null }
    val id = primitive(obj["id"])
    if (id == null || id.length > 200) return null
    val name = primitive(obj["name"])?.filterNot { it.isISOControl() }?.take(70)

    fun strings(key: String): List<String> =
        (obj[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { entry ->
                primitive(entry) ?: (entry as? kotlinx.serialization.json.JsonObject)?.let { nested -> primitive(nested["name"]) }
            }.orEmpty()
    return ConnectionManifest(url = url, id = id, name = name, resources = strings("resources"), drmSystems = strings("drmSystems"))
}

/**
 * Resolve the request hostname and refuse private, loopback, link-local or unspecified addresses. A hostname
 * that *looks* public can still resolve into the local network, so the check runs on the addresses
 * the connection will actually use; inspection, media and license transports all go through it.
 */
internal fun OkHttpClient.Builder.publicPluginDestination(dns: Dns = publicDns()): OkHttpClient.Builder =
    // HTTP CONNECT and SOCKS can resolve the destination remotely, outside this policy.
    proxy(Proxy.NO_PROXY).dns(dns)

internal fun publicDns(resolver: Dns = Dns.SYSTEM): Dns =
    Dns { hostname ->
        val resolved = resolver.lookup(hostname)
        if (resolved.isEmpty() || resolved.any(::isPrivateAddress)) {
            throw UnknownHostException("The destination did not resolve to public addresses")
        }
        resolved
    }

/** Whether [address] is a loopback, link-local, site-local or otherwise non-public destination. */
internal fun isPrivateAddress(address: InetAddress): Boolean {
    if (address.isLoopbackAddress || address.isAnyLocalAddress || address.isLinkLocalAddress ||
        address.isSiteLocalAddress || address.isMulticastAddress
    ) {
        return true
    }
    val bytes = address.address
    if (bytes.size == 4) {
        val first = bytes[0].toInt() and 0xff
        val second = bytes[1].toInt() and 0xff
        // 100.64/10 carrier-grade NAT, 0/8 and 198.18/15 benchmarking ranges.
        if (first == 0 || first >= 240 || (first == 100 && second in 64..127) || (first == 198 && second in 18..19)) return true
    }
    if (bytes.size == 16 && (bytes[0].toInt() and 0xe0) != 0x20) return true
    return false
}

/** Public DNS destinations only: private and link-local literals never receive connection approval. */
internal fun isPrivateConnectionHost(host: String): Boolean {
    val name = host.lowercase().trimEnd('.')
    if (name == "localhost" || name.endsWith(".localhost") || name.endsWith(".internal")) return true
    val labels = name.split('.')
    val octets = labels.map { it.toIntOrNull() }
    if (labels.size == 4 && octets.all { it != null && it in 0..255 }) {
        val (a, b) = octets[0]!! to octets[1]!!
        return a == 0 || a == 10 || a == 127 || (a == 100 && b in 64..127) ||
            (a == 169 && b == 254) || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
    return name.startsWith("[") || name.contains(":")
}
