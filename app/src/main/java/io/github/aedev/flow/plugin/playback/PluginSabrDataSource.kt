package io.github.aedev.flow.plugin.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.aedev.flow.plugin.host.hostAllowed
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException

/** Redact signed URLs, cookies and attestation when a transport grant is rejected. */
internal fun checkedPluginMediaUrl(
    value: String,
    allowedHosts: List<String>,
): HttpUrl {
    val url = value.toHttpUrlOrNull() ?: throw IOException("Invalid plugin media destination")
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
        throw IOException("Plugin media requires an HTTPS destination without user information or fragment")
    }
    if (!hostAllowed(url.host, allowedHosts)) throw IOException("Plugin media destination is not granted")
    return url
}

/**
 * Raw protocol POST transport for one accepted source. A SABR response is an envelope, not audio
 * bytes at a range: never put this factory inside a CacheDataSource or URL/range resolver.
 * OkHttp owns redirects and cancellation; validate every network hop before its body or headers
 * leave the device, and reject redirects that would turn a protocol POST into a GET.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun pluginSabrDataSourceFactory(
    base: OkHttpClient,
    headers: Map<String, String>,
    verifyCurrent: () -> Unit,
    allowedHosts: () -> List<String>,
): DataSource.Factory {
    val acceptedHeaders = headers.toMap()
    val client =
        base
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor(
                Interceptor { chain ->
                    verifyCurrent()
                    checkedPluginMediaUrl(chain.request().url.toString(), allowedHosts())
                    if (chain.request().method != "POST") throw IOException("SABR requires a protocol POST")
                    chain.proceed(chain.request())
                },
            ).build()
    val upstream = OkHttpDataSource.Factory(client)
    return DataSource.Factory {
        ResolvingDataSource(upstream.createDataSource()) { request ->
            verifyCurrent()
            checkedPluginMediaUrl(request.uri.toString(), allowedHosts())
            if (request.httpMethod != DataSpec.HTTP_METHOD_POST || request.httpBody == null || request.position != 0L) {
                throw IOException("SABR requires a protocol POST body")
            }
            val protocolHeaders =
                request.httpRequestHeaders.filterKeys { name ->
                    !name.equals("Range", ignoreCase = true) &&
                        acceptedHeaders.keys.none { it.equals(name, ignoreCase = true) }
                } + acceptedHeaders.filterKeys { !it.equals("Range", ignoreCase = true) }
            request
                .buildUpon()
                .setHttpRequestHeaders(
                    protocolHeaders,
                ).setLength(
                    androidx.media3.common.C.LENGTH_UNSET
                        .toLong(),
                ).setKey(null)
                .build()
        }
    }
}
