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
            durationMs = 120000,
        )

    @Test
    fun `VOD presentations reject absent zero and negative duration while live may omit it`() {
        val errors =
            listOf(null, 0L, -1L).map { duration ->
                val malformed = presentation.copy(durationMs = duration)
                runCatching { validateServerAbr(malformed, false, listOf("cdn.example")) }.exceptionOrNull()?.message
            }
        assertThat(errors).containsExactly(
            "VOD SABR presentation requires a positive duration",
            "VOD SABR presentation requires a positive duration",
            "VOD SABR presentation requires a positive duration",
        )
        validateServerAbr(presentation, false, listOf("cdn.example"))
        validateServerAbr(presentation.copy(live = true, durationMs = null), false, listOf("cdn.example"))
    }

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

    @Test
    fun `unknown codecs and unsupported containers are rejected before native source creation`() {
        val invalidAudio =
            listOf(
                audio.copy(format = audio.format.copy(codecs = "unrecognized-codec")),
                audio.copy(format = audio.format.copy(codecs = "avc1")),
                audio.copy(format = audio.format.copy(mimeType = "audio/ogg")),
                audio.copy(format = audio.format.copy(mimeType = "audio/aac")),
            )
        for (tuple in invalidAudio) {
            assertThat(
                runCatching { validateServerAbr(presentation.copy(formats = listOf(tuple)), false, listOf("cdn.example")) }.isFailure,
            ).isTrue()
        }
        for (tuple in listOf(
            video.copy(format = video.format.copy(codecs = "unrecognized-codec")),
            video.copy(format = video.format.copy(codecs = "opus")),
            video.copy(format = video.format.copy(mimeType = "video/mp2t")),
        )) {
            assertThat(
                runCatching {
                    validateServerAbr(presentation.copy(formats = listOf(audio, tuple)), true, listOf("cdn.example"))
                }.isFailure,
            ).isTrue()
        }
    }

    @Test
    fun `supported audio and video MP4 and WebM codecs remain valid with MIME parameters`() {
        val mp4 = audio.copy(format = audio.format.copy(mimeType = "audio/mp4; codecs=mp4a.40.2", codecs = "mp4a.40.2"))
        val webmPicture = video.copy(format = video.format.copy(mimeType = " Video/WebM ; codecs=vp09.00.51.08", codecs = "vp09.00.51.08"))
        validateServerAbr(presentation.copy(formats = listOf(mp4)), false, listOf("cdn.example"))
        validateServerAbr(
            presentation.copy(formats = listOf(mp4.copy(format = mp4.format.copy(mimeType = "application/mp4")))),
            false,
            listOf("cdn.example"),
        )
        validateServerAbr(presentation.copy(formats = listOf(audio, webmPicture)), true, listOf("cdn.example"))
    }

    @Test
    fun `malformed encoded config or attestation is rejected at the provider boundary`() {
        for (invalid in listOf(
            presentation.copy(config = "x"),
            presentation.copy(config = "%%%"),
            presentation.copy(poToken = "x"),
            presentation.copy(poToken = "AQI==="),
        )) {
            val error = runCatching { validateServerAbr(invalid, false, listOf("cdn.example")) }.exceptionOrNull()
            assertThat(error).isInstanceOf(java.io.IOException::class.java)
            assertThat(error!!.message).doesNotContain(invalid.config)
        }
        validateServerAbr(presentation.copy(config = "-_8=", poToken = "AQI"), false, listOf("cdn.example"))
        validateServerAbr(presentation.copy(poToken = ""), false, listOf("cdn.example"))
    }
}
