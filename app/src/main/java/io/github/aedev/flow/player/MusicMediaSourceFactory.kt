package io.github.aedev.flow.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import io.github.aedev.flow.player.config.PlayerConfig
import io.github.aedev.flow.player.datasource.BoundPluginMusicDataSourceFactory
import io.github.aedev.flow.player.datasource.PluginMusicDataSourceFactory
import io.github.aedev.flow.player.resolver.AudioOnlyHlsPlaylistParserFactory
import io.github.aedev.flow.player.resolver.MediaSourceBuilder
import io.github.aedev.flow.player.resolver.ResolvingMusicMediaSource
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import io.github.aedev.flow.plugin.playback.initializationKeyIds
import io.github.aedev.flow.plugin.playback.pluginDrmInitDataSourceFactory
import io.github.aedev.flow.plugin.playback.pluginDrmProvider
import io.github.aedev.flow.plugin.playback.validatedServiceCertificate
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
                val sourceFactory = audio?.let { resolving.bind(it, mediaItem.mediaId) } ?: dataSourceFallback
                resolvedSource(mediaItem.withAudioOrigin(audio), audio, sourceFactory)
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
                val streamDrm = audio.stream.drm
                native.setDrmSessionManagerProvider(
                    pluginDrmProvider(
                        streamDrm?.let(::validatedServiceCertificate),
                        streamDrm?.multiSession == true,
                        requireNotNull(licenseFactory) { "Plugin DRM requires a permission-checked license transport" },
                    ),
                )
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
        val adaptive = (sourceFactory as? BoundPluginMusicDataSourceFactory)?.adaptive
        audio?.preparedDashManifest?.let { manifest ->
            // Segments of a host-built manifest still pass the plugin's per-request grant check.
            val transport = requireNotNull(adaptive) { "Plugin DASH requires the permission-checked adaptive transport" }
            return MediaSourceBuilder.buildDashSource(transport, manifest, Uri.parse(audio.stream.url), soundItem)
        }

        fun checkedTransport(factory: DataSource.Factory): DataSource.Factory =
            audio?.stream?.drm?.initialization?.let { initialization ->
                pluginDrmInitDataSourceFactory(factory, initialization, initializationKeyIds(initialization))
            } ?: factory
        val progressive = ProgressiveMediaSource.Factory(checkedTransport(sourceFactory))
        val hls = HlsMediaSource.Factory(checkedTransport(sourceFactory))
        val dashMime =
            audio
                ?.stream
                ?.mimeType
                ?.substringBefore(';')
                ?.equals("application/dash+xml", ignoreCase = true) == true
        val dash =
            if (dashMime) {
                DashMediaSource.Factory(
                    checkedTransport(
                        requireNotNull(adaptive) { "Plugin DASH requires the permission-checked adaptive transport" },
                    ),
                )
            } else {
                null
            }
        if (audio?.stream?.drm != null) {
            val streamDrm = audio.stream.drm
            val certificate = streamDrm?.let(::validatedServiceCertificate)
            val checkedLicense =
                requireNotNull(licenseFactory) { "Plugin DRM requires a permission-checked license transport" }
            val provider = pluginDrmProvider(certificate, streamDrm?.multiSession == true, checkedLicense)
            progressive.setDrmSessionManagerProvider(provider)
            hls.setDrmSessionManagerProvider(provider)
            dash?.setDrmSessionManagerProvider(provider)
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
            when (
                audio
                    ?.stream
                    ?.mimeType
                    ?.substringBefore(';')
                    ?.lowercase()
            ) {
                in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl") -> {
                    hls.createMediaSource(soundItem)
                }

                "application/dash+xml" -> {
                    requireNotNull(dash).createMediaSource(
                        // The manifest is fetched from the accepted stream URL; the queue's identity
                        // and metadata stay on the item.
                        soundItem.buildUpon().setUri(requireNotNull(audio).stream.url).build(),
                    )
                }

                else -> {
                    progressive.createMediaSource(soundItem)
                }
            }
        // Separate progressive picture URLs cannot be prepared without fetching hidden bytes.
        // Audio remains available; picture needs a metadata-prepared DASH/HLS/SABR presentation.
        return sound
    }
}
