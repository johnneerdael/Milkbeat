@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.aedev.flow.plugin.playback

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import nl.neerdael.milkbeat.sabr.SabrPlaybackException
import okhttp3.OkHttpClient

internal fun PluginAudio.serverAbrDataSourceFactory(
    audio: ResolvedAudio,
    base: OkHttpClient,
): DataSource.Factory =
    pluginSabrDataSourceFactory(base, audio.stream.headers, {
        verifyBound(audio)
        if (!audio.isValidAt(System.currentTimeMillis(), SystemClock.elapsedRealtime())) {
            throw SabrPlaybackException(SabrPlaybackException.Reason.URL_EXPIRED, audio.stream.serverAbr?.url ?: audio.stream.url, null)
        }
    }) { playbackGrants(audio.pluginId) }

internal fun PluginAudio.adaptiveDataSourceFactory(
    binding: BoundPluginAudio,
    base: OkHttpClient,
    cache: Cache,
): DataSource.Factory = pluginAdaptiveDataSourceFactory(base, binding, cache, ::verifyBound) { playbackGrants(binding.initial.pluginId) }

/** Media and license caches and headers remain separate; current grants apply to every open. */
internal fun PluginAudio.drmDataSourceFactory(
    binding: BoundPluginAudio,
    base: OkHttpClient,
): DataSource.Factory = pluginDrmDataSourceFactory(base, binding) { playbackGrants(binding.initial.pluginId) }
