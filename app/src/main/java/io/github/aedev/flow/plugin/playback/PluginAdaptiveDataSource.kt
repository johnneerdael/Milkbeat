@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.aedev.flow.plugin.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import nl.neerdael.milkbeat.plugin.FormatType
import okhttp3.CookieJar
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.IOException

internal fun pluginAdaptiveDataSourceFactory(
    base: OkHttpClient,
    binding: BoundPluginAudio,
    cache: Cache?,
    verifyCurrent: (ResolvedAudio) -> Unit,
    allowedHosts: () -> List<String>,
): DataSource.Factory {
    val initial = binding.initial
    val formats = listOfNotNull(initial.stream.audioFormat, initial.stream.video).associateBy { it.url }
    val client =
        base
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .addNetworkInterceptor(
                Interceptor { chain ->
                    verifyCurrent(initial)
                    checkedPluginMediaUrl(chain.request().url.toString(), allowedHosts())
                    if (chain.request().method != "GET") throw IOException("Adaptive media requires a GET")
                    chain.proceed(chain.request())
                },
            ).build()
    val network = OkHttpDataSource.Factory(client)
    val upstream: DataSource.Factory =
        if (cache == null) {
            network
        } else {
            CacheDataSource
                .Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(network)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        }
    return DataSource.Factory {
        ResolvingDataSource(upstream.createDataSource()) { request ->
            if (request.httpMethod != DataSpec.HTTP_METHOD_GET) throw IOException("Adaptive media requires a GET")
            verifyCurrent(initial)
            val original = formats[request.uri.toString()] ?: throw IOException("Unknown adaptive rendition")
            val current = runBlocking(Dispatchers.IO) { binding.current() }
            verifyCurrent(current)
            val format =
                when (original.type) {
                    FormatType.AUDIO -> current.stream.audioFormat
                    FormatType.VIDEO -> current.stream.video
                } ?: throw IOException("The provider removed this rendition")
            if (format.id != original.id || format.initRange != original.initRange || format.indexRange != original.indexRange ||
                current.pluginId != initial.pluginId || current.track.ref != initial.track.ref ||
                current.stream.cacheKey != initial.stream.cacheKey
            ) {
                throw IOException("The provider changed this adaptive rendition")
            }
            checkedPluginMediaUrl(format.url, allowedHosts())
            val extra = current.stream.headers + format.headers
            val headers = request.httpRequestHeaders.filterKeys { name -> extra.keys.none { it.equals(name, ignoreCase = true) } } + extra
            request
                .buildUpon()
                .setUri(format.url)
                .setHttpRequestHeaders(headers.filterKeys { !it.equals("Range", ignoreCase = true) })
                .setKey("${current.pluginId}:${current.stream.cacheKey}:${format.id}")
                .build()
        }
    }
}
