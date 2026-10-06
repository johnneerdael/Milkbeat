package io.github.aedev.flow.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.aedev.flow.player.datasource.PluginMusicDataSourceFactory
import io.github.aedev.flow.player.resolver.ResolvingMusicMediaSource
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import nl.neerdael.milkbeat.plugin.AudioDrmScheme

/**
 * The music player's sources. A queue item goes to [default] as ever, except a song whose audio plugin
 * delivers HLS, which plays as a playlist, and a music video, which joins its picture to its sound. Both halves resolve through [dataSourceFactory] by cache key, so the
 * sound is exactly what the song alone would play, visualizer tap included.
 */
@OptIn(UnstableApi::class)
class MusicMediaSourceFactory(
    private val default: MediaSource.Factory,
    dataSourceFactory: DataSource.Factory,
    private val deliversHls: (MediaItem) -> Boolean,
) : MediaSource.Factory by default {
    private val dataSourceFallback = dataSourceFactory
    private val resolver = dataSourceFactory as? PluginMusicDataSourceFactory
    private val progressive = ProgressiveMediaSource.Factory(dataSourceFactory)
    private val hls = HlsMediaSource.Factory(dataSourceFactory)

    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val scheme = mediaItem.localConfiguration?.uri?.scheme
        val resolving = resolver
        if (resolving != null && scheme in setOf(MusicVideoItems.SONG_SCHEME, MusicVideoItems.SCHEME)) {
            return ResolvingMusicMediaSource(mediaItem) {
                val audio = resolving.resolve(mediaItem.localConfiguration!!.uri, scheme == MusicVideoItems.SCHEME)
                val sourceFactory = audio?.let(resolving.bind) ?: dataSourceFallback
                resolvedSource(mediaItem, audio, sourceFactory)
            }
        }
        if (scheme == MusicVideoItems.SONG_SCHEME && deliversHls(mediaItem)) return hls.createMediaSource(mediaItem)
        if (scheme != MusicVideoItems.SCHEME) return default.createMediaSource(mediaItem)
        return resolvedSource(mediaItem, null)
    }

    internal fun resolvedSource(
        mediaItem: MediaItem,
        audio: ResolvedAudio?,
        sourceFactory: DataSource.Factory = dataSourceFallback,
        licenseFactory: DataSource.Factory? = audio?.takeIf { it.stream.drm != null }?.let { resolver?.drm?.invoke(it) },
    ): MediaSource {
        val soundItem =
            audio?.stream?.drm?.let { drm ->
                mediaItem
                    .buildUpon()
                    .setDrmConfiguration(
                        MediaItem.DrmConfiguration
                            .Builder(
                                when (drm.scheme) {
                                    AudioDrmScheme.WIDEVINE -> C.WIDEVINE_UUID
                                },
                            ).setLicenseUri(drm.licenseUrl)
                            .setLicenseRequestHeaders(drm.headers)
                            .setForceDefaultLicenseUri(true)
                            .build(),
                    ).build()
            } ?: mediaItem
        val progressive = ProgressiveMediaSource.Factory(sourceFactory)
        val hls = HlsMediaSource.Factory(sourceFactory)
        if (audio?.stream?.drm != null) {
            val provider = DefaultDrmSessionManagerProvider()
            provider.setDrmHttpDataSourceFactory(
                requireNotNull(licenseFactory) { "Plugin DRM requires a permission-checked license transport" },
            )
            progressive.setDrmSessionManagerProvider(provider)
            hls.setDrmSessionManagerProvider(provider)
        }
        val withPicture = mediaItem.localConfiguration?.uri?.scheme == MusicVideoItems.SCHEME
        val sound =
            if (audio
                    ?.stream
                    ?.mimeType
                    ?.substringBefore(';')
                    ?.lowercase() in
                setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")
            ) {
                hls.createMediaSource(soundItem)
            } else {
                progressive.createMediaSource(soundItem)
            }
        if (!withPicture) return sound
        val videoId = mediaItem.mediaId
        val picture =
            progressive.createMediaSource(
                MediaItem
                    .Builder()
                    .setUri(mediaItem.localConfiguration!!.uri)
                    .setMediaId(videoId)
                    .setCustomCacheKey(MusicVideoItems.videoKey(videoId))
                    .build(),
            )
        // The sound sets the length: a picture that ends early must not cut the song short.
        return MergingMediaSource(true, false, sound, picture)
    }
}
