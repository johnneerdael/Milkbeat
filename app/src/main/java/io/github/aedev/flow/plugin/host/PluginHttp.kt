package io.github.aedev.flow.plugin.host

import android.util.Log
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.player.diagnostics.TraceEvent
import io.github.aedev.flow.player.diagnostics.TraceField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import nl.neerdael.milkbeat.plugin.HttpBodyEncoding
import nl.neerdael.milkbeat.plugin.HttpRequest
import nl.neerdael.milkbeat.plugin.HttpResponse
import nl.neerdael.milkbeat.plugin.PluginJson
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

private const val DEFAULT_TIMEOUT_MS = 20_000L
private const val MAX_TIMEOUT_MS = 60_000L
private const val MAX_RESPONSE_BYTES = 16L * 1024 * 1024
private val BODYLESS_METHODS = setOf("GET", "HEAD")

class PluginHttpException(
    message: String,
) : IOException(message)

/**
 * `mb.http.fetch` for one plugin: the app's HTTP client, restricted to the hosts the listener granted.
 * The check runs on every hop, so a redirect cannot lead a plugin anywhere it was not allowed to go.
 * Plugins handle their own cookies through headers; nothing is shared between plugins.
 */
internal class PluginHttp(
    base: OkHttpClient,
    private val allowedHosts: List<String>,
) {
    private val client =
        base
            .newBuilder()
            .cookieJar(okhttp3.CookieJar.NO_COOKIES)
            .addNetworkInterceptor(Interceptor { chain -> chain.proceed(checked(chain.request())) })
            .build()

    suspend fun fetch(request: HttpRequest): HttpResponse =
        withContext(Dispatchers.IO) {
            val method = request.method.uppercase()
            val url = checked(Request.Builder().url(request.url).build()).url
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
                    .followRedirects(request.followRedirects)
                    .followSslRedirects(request.followRedirects)
                    .callTimeout((request.timeoutMs ?: DEFAULT_TIMEOUT_MS).coerceAtMost(MAX_TIMEOUT_MS), TimeUnit.MILLISECONDS)
                    .build()
                    .newCall(
                        Request
                            .Builder()
                            .url(url)
                            .apply { request.headers.forEach { (name, value) -> header(name, value) } }
                            .method(method, body ?: if (method in BODYLESS_METHODS) null else ByteArray(0).toRequestBody())
                            .build(),
                    )
            val trace = PlaybackTrace.start(TraceEvent.HTTP_STARTED, pluginHttpTraceCategory(url))
            var status = 0L
            var size = 0L
            var success = false
            try {
                call.execute().use { response ->
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
                    if (BuildConfig.DEBUG && url.host == "music.youtube.com" && url.encodedPath.endsWith("/browse") &&
                        request.body?.contains("\"browseId\":\"VL") == true
                    ) {
                        Log.i(
                            "PlaylistMirrorHttp",
                            "Playlist response: " +
                                "status=${response.code}, keys=${runCatching {
                                    PluginJson
                                        .parseToJsonElement(
                                            responseText,
                                        ).jsonObject.keys
                                }.getOrNull()}, " +
                                "editable=${responseText.contains("musicEditablePlaylistDetailHeaderRenderer")}, " +
                                "marker=${responseText.contains("[milkbeat-mirror:")}, " +
                                "legacyHeader=${responseText.contains("musicDetailHeaderRenderer")}, " +
                                "responsiveHeader=${responseText.contains("musicResponsiveHeaderRenderer")}",
                        )
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
        if (!url.isHttps) throw PluginHttpException("Plugins may only use HTTPS: ${url.host}")
        if (!hostAllowed(url.host, allowedHosts)) throw PluginHttpException("${url.host} is not in the plugin's permissions")
        return request
    }
}
