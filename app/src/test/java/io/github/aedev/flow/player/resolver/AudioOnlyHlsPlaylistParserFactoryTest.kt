package io.github.aedev.flow.player.resolver

import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioOnlyHlsPlaylistParserFactoryTest {
    private fun parse(
        value: String,
        trustedAudio: Boolean = false,
    ) = AudioOnlyHlsPlaylistParserFactory(trustedAudio)
        .createPlaylistParser()
        .parse(Uri.parse("https://cdn.example/master.m3u8"), value.toByteArray().inputStream())

    @Test
    fun `advertised separate audio is selected without a video variant URI`() {
        val master = """#EXTM3U
#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="sound",NAME="English",DEFAULT=YES,URI="audio.m3u8"
#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2",AUDIO="sound",RESOLUTION=1920x1080
video.m3u8
"""
        val selected = parse(master) as HlsMultivariantPlaylist
        assertThat(selected.variants).hasSize(1)
        assertThat(selected.variants[0].url.toString()).isEqualTo("https://cdn.example/audio.m3u8")
        assertThat(selected.mediaPlaylistUrls.map { it.toString() }).doesNotContain("https://cdn.example/video.m3u8")
    }

    @Test
    fun `an advertised audio-only variant retains its codec and discards video variants`() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=128000,CODECS="mp4a.40.2"
audio.m3u8
#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080
video.m3u8
"""
        val selected = parse(master) as HlsMultivariantPlaylist
        assertThat(selected.variants).hasSize(1)
        assertThat(selected.variants[0].format.codecs).isEqualTo("mp4a.40.2")
        assertThat(selected.variants[0].url.toString()).endsWith("audio.m3u8")
    }

    @Test
    fun `muxed-only video manifests fail before any media segment is requested`() {
        val master = """#EXTM3U
#EXT-X-STREAM-INF:BANDWIDTH=5000000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080
video.m3u8
"""
        assertThat(runCatching { parse(master) }.isFailure).isTrue()
        val media = """#EXTM3U
#EXT-X-TARGETDURATION:10
#EXTINF:10,
segment.ts
#EXT-X-ENDLIST
"""
        assertThat(runCatching { parse(media) }.isFailure).isTrue()
        assertThat(parse(media, trustedAudio = true)).isInstanceOf(HlsMediaPlaylist::class.java)
    }
}
