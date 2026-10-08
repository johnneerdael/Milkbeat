package io.github.aedev.flow.utils

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.MusicVideoItems
import org.junit.Test

class MusicVideoFormatsTest {
    @Test
    fun `physical display mode bounds music picture requests up to 2160p`() {
        assertThat(MusicVideoFormats.heightForDisplay(3840, 2160)).isEqualTo(2160)
        assertThat(MusicVideoFormats.heightForDisplay(1920, 1080)).isEqualTo(1080)
        assertThat(MusicVideoFormats.heightForDisplay(1280, 720)).isEqualTo(720)
        assertThat(MusicVideoFormats.heightForDisplay(7680, 4320)).isEqualTo(2160)
    }

    @Test
    fun `portrait and unknown modes retain conservative music picture limits`() {
        assertThat(MusicVideoFormats.heightForDisplay(1080, 1920)).isEqualTo(1080)
        assertThat(MusicVideoFormats.heightForDisplay(null, null)).isEqualTo(1080)
    }

    @Test
    fun `a music video's picture is cached apart from its sound`() {
        assertThat(MusicVideoItems.videoKey("abc")).isNotEqualTo("abc")
        assertThat(MusicVideoItems.videoIdOfVideoKey(MusicVideoItems.videoKey("abc"))).isEqualTo("abc")
        assertThat(MusicVideoItems.videoIdOfVideoKey("abc")).isNull()
    }
}
