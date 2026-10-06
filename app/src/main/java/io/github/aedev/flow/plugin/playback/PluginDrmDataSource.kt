package io.github.aedev.flow.plugin.playback

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import io.github.aedev.flow.plugin.host.hostAllowed
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException

/** Never include a signed license URL or its credentials in a validation error. */
internal fun checkedPluginDrmUrl(
    value: String,
    allowedHosts: List<String>,
): HttpUrl {
    val url = value.toHttpUrlOrNull() ?: throw IOException("Invalid plugin DRM destination")
    if (!url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
        throw IOException("Plugin DRM requires an HTTPS destination without user information or fragment")
    }
    if (!hostAllowed(url.host, allowedHosts)) throw IOException("Plugin DRM destination is not granted")
    return url
}

/**
 * Media3 follows license POST redirects itself. Check each open before any challenge or headers
 * leave the device, including redirected licenses and provisioning. Read current grants on every
 * open so removing or disabling a plugin also revokes a previously resolved license.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal fun pluginDrmDataSourceFactory(
    base: OkHttpClient,
    allowedHosts: () -> List<String>,
): DataSource.Factory {
    val client =
        base
            .newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    return ResolvingDataSource.Factory(OkHttpDataSource.Factory(client)) { request ->
        checkedPluginDrmUrl(request.uri.toString(), allowedHosts())
        request
    }
}
