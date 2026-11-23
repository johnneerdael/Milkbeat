package io.github.aedev.flow.plugin.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.aedev.flow.plugin.host.publicDns
import io.github.aedev.flow.plugin.host.publicPluginDestination
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.io.IOException

/**
 * Cache-backed progressive and HLS media for one accepted binding. Every open and redirect must
 * still be a currently granted HTTPS destination, so removing a reviewed connection withdraws its
 * origin on the next request instead of only when the track is resolved again.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun pluginMediaDataSourceFactory(
    base: OkHttpClient,
    cache: Cache?,
    verifyCurrent: () -> Unit,
    allowedHosts: () -> List<String>,
    dns: Dns = publicDns(),
): DataSource.Factory {
    val client =
        base
            .newBuilder()
            .publicPluginDestination(dns)
            .cookieJar(CookieJar.NO_COOKIES)
            .addNetworkInterceptor { chain ->
                verifyCurrent()
                checkedPluginMediaUrl(chain.request().url.toString(), allowedHosts())
                if (chain.request().method != "GET") throw IOException("Plugin media requires a GET")
                chain.proceed(chain.request())
            }.build()
    val upstream = pluginRangedDataSourceFactory(OkHttpDataSource.Factory(client))
    return if (cache == null) {
        upstream
    } else {
        CacheDataSource
            .Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }
}
