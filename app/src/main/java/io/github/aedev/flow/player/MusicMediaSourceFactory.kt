package io.github.aedev.flow.player

import android.net.Uri
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
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.datasource.BoundPluginMusicDataSourceFactory
import io.github.aedev.flow.player.datasource.PluginMusicDataSourceFactory
import io.github.aedev.flow.player.resolver.AdaptiveDashManifest
import io.github.aedev.flow.player.resolver.AudioOnlyHlsPlaylistParserFactory
import io.github.aedev.flow.player.resolver.MediaSourceBuilder
import io.github.aedev.flow.player.resolver.ResolvingMusicMediaSource
import io.github.aedev.flow.plugin.playback.PluginVideoStreams
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import nl.neerdael.milkbeat.plugin.AudioDrmScheme
import nl.neerdael.milkbeat.sabr.SabrMediaSource

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
        licenseFactory: DataSource.Factory? = (sourceFactory as? BoundPluginMusicDataSourceFactory)?.drm,
    ): MediaSource {
        // Source metadata belongs to the accepted recording. Queue/catalog identity stays intact;
        // a prefetched source cannot publish artwork until its timeline becomes the playing window.
        val acceptedItem =
            audio?.stream?.artwork?.url?.takeIf(String::isNotBlank)?.let { url ->
                mediaItem
                    .buildUpon()
                    .setMediaMetadata(
                        mediaItem.mediaMetadata
                            .buildUpon()
                            .setArtworkUri(Uri.parse(url))
                            .build(),
                    ).build()
            } ?: mediaItem
        val soundItem =
            audio?.stream?.drm?.let { drm ->
                acceptedItem
                    .buildUpon()
                    .setDrmConfiguration(
                        MediaItem.DrmConfiguration
                            .Builder(
                                when (drm.scheme) {
                                    AudioDrmScheme.WIDEVINE -> C.WIDEVINE_UUID
                                },
                            ).setLicenseUri(drm.licenseUrl)
                            .setForceDefaultLicenseUri(true)
                            .build(),
                    ).build()
            } ?: acceptedItem
        audio?.stream?.let {
            io.github.aedev.flow.plugin.playback
                .requireNativeSabrMarker(it.mimeType, it.serverAbr)
        }
        audio?.stream?.serverAbr?.let { presentation ->
            val transport = (sourceFactory as? BoundPluginMusicDataSourceFactory)?.serverAbr ?: sourceFactory
            val native =
                SabrMediaSource
                    .Factory(transport)
                    .setLivePresentationDelayMs(PlayerConfig.LIVE_EDGE_GAP_MS, true)
            if (audio.stream.drm != null) {
                val provider = DefaultDrmSessionManagerProvider()
                provider.setDrmHttpDataSourceFactory(
                    requireNotNull(licenseFactory) { "Plugin DRM requires a permission-checked license transport" },
                )
                native.setDrmSessionManagerProvider(provider)
            }
            // The presentation already contains either audio-only formats or audio + picture.
            // Preserve the exact accepted item; never bolt progressive picture bytes onto SABR.
            val source = native.createMediaSource(soundItem, presentation)
            val hold = (sourceFactory as? BoundPluginMusicDataSourceFactory)?.runtimeHold
            return if (hold == null) {
                source
            } else {
                io.github.aedev.flow.player.resolver
                    .RuntimeHeldMediaSource(source, hold)
            }
        }
        val stream = audio?.stream
        if (stream?.drm == null && stream?.audioFormat != null && stream.video != null) {
            val sound = PluginVideoStreams.audioStreams(listOf(stream.audioFormat!!)).singleOrNull()
            val pictures = PluginVideoStreams.videoStreams(listOf(stream.video!!))
            val durationMs =
                listOfNotNull(stream.audioFormat?.durationMs, stream.video?.durationMs, audio.track.durationMs).maxOrNull() ?: 0L
            if (sound != null && stream.audioFormat?.initRange != null && stream.audioFormat?.indexRange != null) {
                AdaptiveDashManifest.build(pictures, sound, (durationMs + 999L) / 1000L)?.let { manifest ->
                    val headers =
                        StreamRequestHeaders(
                            stream.headers,
                            listOf(stream.audioFormat!!, stream.video!!).associate { it.url to it.headers },
                        )
                    val transport =
                        (sourceFactory as? BoundPluginMusicDataSourceFactory)?.adaptive ?: sourceFactory.withRequestHeaders(headers)
                    return MediaSourceBuilder.buildDashSource(transport, manifest, Uri.parse(stream.url), soundItem)
                }
            }
        }
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
        if (!withPicture || audio?.stream?.requireAudioOnlyHls == true) {
            hls.setPlaylistParserFactory(
                AudioOnlyHlsPlaylistParserFactory(
                    allowInitialMediaPlaylist =
                        audio?.stream?.requireAudioOnlyHls != true,
                ),
            )
        }
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
        if (!withPicture || sound is HlsMediaSource || (audio != null && audio.stream.video == null)) return sound
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
