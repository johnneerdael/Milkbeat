package io.github.aedev.flow.player.resolver

import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.PluginVideoStreams
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class VideoPlaybackResolverAudioFallbackTest {
    private val data = DataSource.Factory { ByteArrayDataSource(byteArrayOf(0)) }
    private val audio = PluginVideoStreams.audioStreams(listOf(PluginVideoStreamsTest.audioOriginal)).single()
    private val video = PluginVideoStreams.videoStreams(listOf(PluginVideoStreamsTest.video1080))
    private val hls = "https://media.example/muxed-master.m3u8"
    private val metadata = MediaMetadata.Builder().setTitle("Accepted recording").build()

    @Test
    fun `audio only prefers selected progressive sound over uninspected muxed HLS`() {
        val source =
            VideoPlaybackResolver(data, data, mediaId = "accepted-id", mediaMetadata = metadata)
                .resolve(video, audio, null, hls, 0, audioOnly = true)
        assertThat(source).isInstanceOf(ProgressiveMediaSource::class.java)
        assertThat(
            source!!
                .mediaItem.localConfiguration!!
                .uri
                .toString(),
        ).isEqualTo(audio.content)
        assertThat(source.mediaItem.mediaId).isEqualTo("accepted-id")
        assertThat(source.mediaItem.mediaMetadata).isEqualTo(metadata)
    }

    @Test
    fun `picture request and absent separate sound retain HLS delivery`() {
        val resolver = VideoPlaybackResolver(data, data)
        assertThat(resolver.resolve(video, audio, null, hls, 0, audioOnly = false)).isInstanceOf(HlsMediaSource::class.java)
        assertThat(resolver.resolve(emptyList(), null, null, hls, 0, audioOnly = true)).isInstanceOf(HlsMediaSource::class.java)
    }
}
