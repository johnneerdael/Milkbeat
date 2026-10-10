package io.github.aedev.flow.player.audio

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayingAudioFormatsTest {
    private val first = MediaPeriodId("first", 0)
    private val second = MediaPeriodId("second", 1)

    private fun aac(bitrate: Int): Format =
        Format
            .Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setAverageBitrate(bitrate)
            .build()

    @Test
    fun `the playing period's input format is shown at once`() {
        val formats = PlayingAudioFormats()

        assertThat(formats.onInput(first, playing = first, aac(256_000))).isEqualTo(aac(256_000))
    }

    @Test
    fun `an adaptive switch on the playing period replaces the shown rendition`() {
        val formats = PlayingAudioFormats()
        formats.onInput(first, playing = first, aac(64_000))

        assertThat(formats.onInput(first, playing = first, aac(256_000))).isEqualTo(aac(256_000))
        assertThat(formats.onPlaying(first)).isEqualTo(aac(256_000))
    }

    @Test
    fun `a format read ahead for the next track waits until that track plays`() {
        val formats = PlayingAudioFormats()
        formats.onInput(first, playing = first, aac(256_000))

        assertThat(formats.onInput(second, playing = first, aac(128_000))).isNull()
        assertThat(formats.onPlaying(second)).isEqualTo(aac(128_000))
    }

    @Test
    fun `a period whose audio is not read yet has no format`() {
        val formats = PlayingAudioFormats()

        assertThat(formats.onPlaying(second)).isNull()
        assertThat(formats.onPlaying(null)).isNull()
    }

    @Test
    fun `only the most recent periods are kept`() {
        val formats = PlayingAudioFormats()
        val periods = (0L until 6L).map { MediaPeriodId("p$it", it) }
        periods.forEach { formats.onInput(it, playing = null, aac(128_000)) }

        assertThat(formats.onPlaying(periods.first())).isNull()
        assertThat(formats.onPlaying(periods.last())).isEqualTo(aac(128_000))
    }
}
