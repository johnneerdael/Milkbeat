package io.github.aedev.flow.player

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.datasource.BoundPluginMusicDataSourceFactory
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import io.github.aedev.flow.plugin.playback.hasPreparedPicture
import io.mockk.mockk
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioDrm
import nl.neerdael.milkbeat.plugin.AudioDrmScheme
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.ByteRange
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.ServerAbrClientInfo
import nl.neerdael.milkbeat.plugin.ServerAbrFormat
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import nl.neerdael.milkbeat.sabr.SabrMediaSource
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicMediaSourceFactoryTest {
    private val factory = MusicMediaSourceFactory(mockk<MediaSource.Factory>(), mockk<DataSource.Factory>()) { false }
    private val item =
        MediaItem
            .Builder()
            .setUri(Uri.parse("music://spotify-song"))
            .setMediaId("spotify-song")
            .build()

    @Test
    fun `prepared direct audio and picture share one DASH source`() {
        val sound =
            MediaFormat(
                "251:123",
                FormatType.AUDIO,
                "https://cdn.example/audio",
                "audio/webm",
                codecs = "opus",
                bitrate = 128000,
                durationMs = 120000,
                initRange = ByteRange(0, 258),
                indexRange = ByteRange(259, 14537),
            )
        val picture =
            MediaFormat(
                "313:124",
                FormatType.VIDEO,
                "https://cdn.example/video",
                "video/webm",
                codecs = "vp9",
                width = 3840,
                height = 2160,
                bitrate = 12000000,
                durationMs = 120000,
                initRange = ByteRange(0, 220),
                indexRange = ByteRange(221, 27854),
            )
        val resolution =
            ResolvedAudio(
                "youtube",
                TrackDescriptor(EntityRef(EntityKind.TRACK, "matched"), "Matched"),
                AudioStream(sound.url, "matched", sound.id, sound.mimeType, codecs = sound.codecs, video = picture, audioFormat = sound),
                Long.MAX_VALUE,
                false,
            )
        val source = factory.resolvedSource(item.buildUpon().setUri("musicvideo://spotify-song").build(), resolution)
        assertThat(source).isInstanceOf(DashMediaSource::class.java)
        assertThat(source.mediaItem.mediaId).isEqualTo(item.mediaId)
    }

    @Test
    fun `prepared audio only fallback does not construct a missing picture source`() {
        val source = factory.resolvedSource(item.buildUpon().setUri("musicvideo://spotify-song").build(), audio("audio/mp4"))
        assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
    }

    @Test
    fun `legacy separate progressive picture is not prepared behind hidden audio`() {
        val legacy = audio("audio/mp4")
        val resolved =
            ResolvedAudio(
                legacy.pluginId,
                legacy.track,
                legacy.stream.copy(video = MediaFormat("137", FormatType.VIDEO, "https://fixture/picture", "video/mp4")),
                Long.MAX_VALUE,
                true,
            )
        val source = factory.resolvedSource(item.buildUpon().setUri("musicvideo://spotify-song").build(), resolved)
        assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
        assertThat(resolved.hasPreparedPicture).isFalse()
    }

    @Test
    fun `completed unbound audio does not ask for a missing downloaded picture`() {
        val cached = item.buildUpon().setUri("musicvideo://spotify-song").build()
        assertThat(factory.resolvedSource(cached, null)).isInstanceOf(ProgressiveMediaSource::class.java)
    }

    @Test
    fun `a Beatport fallback chooses HLS from the resolved stream`() {
        assertThat(factory.resolvedSource(item, audio("application/x-mpegURL"))).isInstanceOf(HlsMediaSource::class.java)
        assertThat(
            factory.resolvedSource(item, audio("application/vnd.apple.mpegurl; charset=utf-8")),
        ).isInstanceOf(HlsMediaSource::class.java)
    }

    @Test
    fun `YouTube and downloaded audio retain progressive sources`() {
        assertThat(factory.resolvedSource(item, audio("audio/mp4"))).isInstanceOf(ProgressiveMediaSource::class.java)
        assertThat(factory.resolvedSource(item, null)).isInstanceOf(ProgressiveMediaSource::class.java)
    }

    @Test
    fun `encrypted HLS uses the provider license rather than a manifest license`() {
        val drm = AudioDrm(AudioDrmScheme.WIDEVINE, "https://license.example/playback", mapOf("Authorization" to "fixture"))
        val bound = BoundPluginMusicDataSourceFactory(mockk<DataSource.Factory>(), mockk<DataSource.Factory>())
        val source = factory.resolvedSource(item, audio("application/x-mpegURL", drm), sourceFactory = bound)
        val configuration = source.mediaItem.localConfiguration!!.drmConfiguration
        assertThat(configuration).isNotNull()
        assertThat(configuration!!.scheme).isEqualTo(C.WIDEVINE_UUID)
        assertThat(configuration.licenseUri.toString()).isEqualTo(drm.licenseUrl)
        assertThat(configuration.licenseRequestHeaders).isEmpty()
        assertThat(configuration.forceDefaultLicenseUri).isTrue()
    }

    @Test
    fun `DRM source creation fails closed without a dedicated license transport`() {
        val drm = AudioDrm(AudioDrmScheme.WIDEVINE, "https://license.example/playback")
        assertThat(runCatching { factory.resolvedSource(item, audio("application/x-mpegURL", drm)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `clear streams retain their media item without DRM`() {
        for (mime in listOf("application/x-mpegURL", "audio/mp4")) {
            val source = factory.resolvedSource(item, audio(mime))
            assertThat(source.mediaItem).isEqualTo(item)
            assertThat(source.mediaItem.localConfiguration!!.drmConfiguration).isNull()
        }
    }

    @Test
    fun `accepted playback artwork retains original catalog identity and metadata`() {
        val original =
            item
                .buildUpon()
                .setCustomCacheKey("spotify-song")
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle("Spotify title")
                        .setArtist("Spotify artist")
                        .setArtworkUri(Uri.parse("https://catalog.example/cover.jpg"))
                        .build(),
                ).build()
        val resolved = audio("audio/mp4", artwork = Artwork("https://playback.example/maxres.jpg", 1920, 1080))

        val accepted = factory.resolvedSource(original, resolved).mediaItem

        assertThat(accepted.mediaMetadata.artworkUri.toString()).isEqualTo(resolved.stream.artwork!!.url)
        assertThat(accepted.mediaId).isEqualTo(original.mediaId)
        assertThat(accepted.localConfiguration).isEqualTo(original.localConfiguration)
        assertThat(accepted.mediaMetadata.title).isEqualTo("Spotify title")
        assertThat(accepted.mediaMetadata.artist).isEqualTo("Spotify artist")
    }

    @Test
    fun `missing or blank playback artwork retains the original cover`() {
        val original =
            item
                .buildUpon()
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setArtworkUri(Uri.parse("https://catalog.example/cover.jpg"))
                        .build(),
                ).build()
        for (artwork in listOf(null, Artwork("  "))) {
            assertThat(factory.resolvedSource(original, audio("audio/mp4", artwork = artwork)).mediaItem.mediaMetadata)
                .isEqualTo(original.mediaMetadata)
        }
    }

    @Test
    fun `SABR-only music uses the native source and exact accepted catalog item`() {
        val original =
            item
                .buildUpon()
                .setCustomCacheKey("spotify-song")
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle("Original title")
                        .setArtist("Original artist")
                        .build(),
                ).build()
        val stream = audio("application/x-server-abr", artwork = Artwork("https://image.example/maxres.jpg"), serverAbr = presentation())
        val source = factory.resolvedSource(original, stream)
        assertThat(source).isInstanceOf(SabrMediaSource::class.java)
        assertThat(source.mediaItem.mediaId).isEqualTo(original.mediaId)
        assertThat(source.mediaItem.localConfiguration).isEqualTo(original.localConfiguration)
        assertThat(source.mediaItem.mediaMetadata.title).isEqualTo("Original title")
        assertThat(source.mediaItem.mediaMetadata.artist).isEqualTo("Original artist")
        assertThat(
            source.mediaItem.mediaMetadata.artworkUri
                .toString(),
        ).isEqualTo("https://image.example/maxres.jpg")
    }

    @Test
    fun `SABR presentation with picture remains one native source without progressive merge`() {
        val video = MediaFormat("399", FormatType.VIDEO, "", "video/mp4", codecs = "av01.0.12M.08", width = 3840, height = 2160)
        val native = presentation().let { it.copy(formats = it.formats + ServerAbrFormat(video, 399, "123")) }
        val source =
            factory.resolvedSource(
                item.buildUpon().setUri("${MusicVideoItems.SCHEME}://spotify-song").build(),
                audio("application/x-server-abr", serverAbr = native),
            )
        assertThat(source).isInstanceOf(SabrMediaSource::class.java)
    }

    @Test
    fun `HLS master with picture stays one native HLS source rather than a progressive picture merge`() {
        val source =
            factory.resolvedSource(
                item.buildUpon().setUri("${MusicVideoItems.SCHEME}://spotify-song").build(),
                audio("application/x-mpegURL"),
            )
        assertThat(source).isInstanceOf(HlsMediaSource::class.java)
    }

    private fun presentation() =
        ServerAbrPlayback(
            "https://cdn.example/videoplayback",
            "matched-video",
            "dXBzdHJlYW0=",
            ServerAbrClientInfo(7, "fixture"),
            listOf(ServerAbrFormat(MediaFormat("251", FormatType.AUDIO, "", "audio/webm", codecs = "opus"), 251, "18446744073709551615")),
        )

    private fun audio(
        mime: String,
        drm: AudioDrm? = null,
        artwork: Artwork? = null,
        serverAbr: ServerAbrPlayback? = null,
    ): ResolvedAudio =
        ResolvedAudio(
            "provider",
            TrackDescriptor(EntityRef(EntityKind.TRACK, "resolved"), "Song"),
            AudioStream("https://fixture/audio", "resolved", "aac", mime, drm = drm, artwork = artwork, serverAbr = serverAbr),
            Long.MAX_VALUE,
            false,
        )
}
