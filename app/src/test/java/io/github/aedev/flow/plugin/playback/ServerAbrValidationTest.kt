package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.ServerAbrClientInfo
import nl.neerdael.milkbeat.plugin.ServerAbrFormat
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import org.junit.Test

class ServerAbrValidationTest {
    private val audio =
        ServerAbrFormat(MediaFormat("251", FormatType.AUDIO, "", "audio/webm", codecs = "opus"), 251, "18446744073709551615")
    private val video =
        ServerAbrFormat(
            MediaFormat("399", FormatType.VIDEO, "", "video/mp4", codecs = "av01.0.12M.08", width = 3840, height = 2160),
            399,
            "123",
            "fixture",
        )
    private val presentation =
        ServerAbrPlayback(
            "https://cdn.example/videoplayback?token=secret",
            "accepted-video",
            "dXBzdHJlYW0=",
            ServerAbrClientInfo(7, "fixture"),
            listOf(audio),
        )

    @Test
    fun `accepted picture presentations require a native video tuple`() {
        assertThat(runCatching { validateServerAbr(presentation, true, listOf("cdn.example")) }.isFailure).isTrue()
        validateServerAbr(presentation, false, listOf("cdn.example"))
    }

    @Test
    fun `native formats accept empty direct URLs and exact unsigned format discriminator`() {
        validateServerAbr(presentation, false, listOf("cdn.example"))
        validateServerAbr(presentation.copy(formats = listOf(audio, video)), true, listOf("cdn.example"))
    }

    @Test
    fun `audio-only rejects all picture formats and invalid identities before transport`() {
        for (invalid in listOf(
            presentation.copy(formats = listOf(audio, video)),
            presentation.copy(formats = listOf(audio, audio)),
            presentation.copy(formats = listOf(audio.copy(lastModified = "18446744073709551616"))),
            presentation.copy(formats = listOf(video)),
        )) {
            assertThat(runCatching { validateServerAbr(invalid, false, listOf("cdn.example")) }.isFailure).isTrue()
        }
    }

    @Test
    fun `a protocol MIME without a native marker never falls through to progressive`() {
        assertThat(runCatching { requireNativeSabrMarker("application/x-server-abr", null) }.isFailure).isTrue()
        requireNativeSabrMarker("audio/mp4", null)
    }

    @Test
    fun `ungranted SABR endpoints fail with a redacted error`() {
        val error = runCatching { validateServerAbr(presentation, false, emptyList()) }.exceptionOrNull()
        assertThat(error).isNotNull()
        assertThat(error!!.message).doesNotContain("secret")
    }
}
