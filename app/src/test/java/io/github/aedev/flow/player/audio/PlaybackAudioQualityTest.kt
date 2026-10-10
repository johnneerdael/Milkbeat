package io.github.aedev.flow.player.audio

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.Tracks
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.folders.MusicFolderKind
import org.junit.Test

class PlaybackAudioQualityTest {
    private val deezer = AudioQualitySource.Provider("Deezer")

    private fun audio(
        mime: String,
        sampleRate: Int = 44_100,
        channels: Int = 2,
        averageBitrate: Int = Format.NO_VALUE,
        peakBitrate: Int = Format.NO_VALUE,
        pcmEncoding: Int = Format.NO_VALUE,
        codecs: String? = null,
    ): Format =
        Format
            .Builder()
            .setSampleMimeType(mime)
            .setSampleRate(sampleRate)
            .setChannelCount(channels)
            .setAverageBitrate(averageBitrate)
            .setPeakBitrate(peakBitrate)
            .setPcmEncoding(pcmEncoding)
            .setCodecs(codecs)
            .build()

    @Test
    fun `a CD quality FLAC shows its decoded rate and depth, as providers label it`() {
        val flac = audio(MimeTypes.AUDIO_FLAC, pcmEncoding = C.ENCODING_PCM_16BIT)

        val quality = playbackAudioQuality("id", deezer, flac, DeclaredAudio("audio/flac", null, 912_000))!!

        assertThat(quality.codec).isEqualTo(AudioCodec.FLAC)
        assertThat(quality.bitrateKbps).isEqualTo(1411)
        assertThat(quality.sampleRateHz).isEqualTo(44_100)
        assertThat(quality.bitDepth).isEqualTo(16)
        assertThat(quality.source).isEqualTo(deezer)
    }

    @Test
    fun `a hi-res FLAC shows its own rate and depth`() {
        val flac = audio(MimeTypes.AUDIO_FLAC, sampleRate = 96_000, pcmEncoding = C.ENCODING_PCM_24BIT)

        val quality = playbackAudioQuality("id", AudioQualitySource.Local, flac, null)!!

        assertThat(quality.bitrateKbps).isEqualTo(4608)
        assertThat(quality.bitDepth).isEqualTo(24)
    }

    @Test
    fun `an MP3 shows the bitrate its frames carry, not what the plugin declared`() {
        val mp3 = audio(MimeTypes.AUDIO_MPEG, averageBitrate = 320_000)

        val quality = playbackAudioQuality("id", deezer, mp3, DeclaredAudio("audio/mpeg", null, 128_000))!!

        assertThat(quality.codec).isEqualTo(AudioCodec.MP3)
        assertThat(quality.bitrateKbps).isEqualTo(320)
        assertThat(quality.bitDepth).isNull()
    }

    @Test
    fun `a stream without its own bitrate falls back to the declared one`() {
        val opus = audio(MimeTypes.AUDIO_OPUS, sampleRate = 48_000)

        val quality = playbackAudioQuality("id", deezer, opus, DeclaredAudio("audio/webm", "opus", 160_000))!!

        assertThat(quality.codec).isEqualTo(AudioCodec.OPUS)
        assertThat(quality.bitrateKbps).isEqualTo(160)
    }

    @Test
    fun `an AAC profile is named from its RFC 6381 codec string`() {
        assertThat(playbackAudioQuality("id", null, audio(MimeTypes.AUDIO_AAC, codecs = "mp4a.40.2"), null)!!.codec)
            .isEqualTo(AudioCodec.AAC)
        assertThat(playbackAudioQuality("id", null, audio(MimeTypes.AUDIO_AAC, codecs = "mp4a.40.5"), null)!!.codec)
            .isEqualTo(AudioCodec.HE_AAC)
        assertThat(playbackAudioQuality("id", null, audio(MimeTypes.AUDIO_AAC, codecs = "mp4a.40.29"), null)!!.codec)
            .isEqualTo(AudioCodec.HE_AAC_V2)
    }

    @Test
    fun `a peak bitrate is used when no average is known`() {
        val aac = audio(MimeTypes.AUDIO_AAC, peakBitrate = 256_000)

        assertThat(playbackAudioQuality("id", null, aac, null)!!.bitrateKbps).isEqualTo(256)
    }

    @Test
    fun `an unknown codec keeps its MIME subtype`() {
        val ape = audio("audio/x-ape")

        val quality = playbackAudioQuality("id", null, ape, null)!!

        assertThat(quality.codec).isNull()
        assertThat(quality.otherCodec).isEqualTo("X-APE")
        assertThat(quality.bitrateKbps).isNull()
    }

    @Test
    fun `a declared container type does not stand in for the codec`() {
        val quality = playbackAudioQuality("id", deezer, null, DeclaredAudio("audio/mp4", null, 256_000))!!

        assertThat(quality.codec).isNull()
        assertThat(quality.otherCodec).isNull()
        assertThat(quality.bitrateKbps).isEqualTo(256)
    }

    @Test
    fun `nothing is known without a track or a declaration`() {
        assertThat(playbackAudioQuality("id", deezer, null, null)).isNull()
    }

    @Test
    fun `the selected audio track is the one described`() {
        val low = audio(MimeTypes.AUDIO_AAC, averageBitrate = 64_000)
        val high = audio(MimeTypes.AUDIO_AAC, averageBitrate = 256_000)
        val picture = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val tracks =
            Tracks(
                listOf(
                    Tracks.Group(TrackGroup(picture), false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true)),
                    Tracks.Group(
                        TrackGroup(low, high),
                        true,
                        intArrayOf(C.FORMAT_HANDLED, C.FORMAT_HANDLED),
                        booleanArrayOf(false, true),
                    ),
                ),
            )

        assertThat(tracks.selectedAudioFormat()).isEqualTo(high)
        assertThat(Tracks.EMPTY.selectedAudioFormat()).isNull()
    }

    @Test
    fun `sources are named by scheme or by the resolving plugin`() {
        assertThat(audioQualitySource("smbmusic", null)).isEqualTo(AudioQualitySource.Folder(MusicFolderKind.SMB))
        assertThat(audioQualitySource("nfsmusic", null)).isEqualTo(AudioQualitySource.Folder(MusicFolderKind.NFS))
        assertThat(audioQualitySource("content", null)).isEqualTo(AudioQualitySource.Local)
        assertThat(audioQualitySource("file", null)).isEqualTo(AudioQualitySource.Local)
        assertThat(audioQualitySource("music", "Deezer")).isEqualTo(deezer)
        assertThat(audioQualitySource("music", null)).isNull()
    }
}
