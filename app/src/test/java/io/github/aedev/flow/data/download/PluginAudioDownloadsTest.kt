package io.github.aedev.flow.data.download

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.plugin.AudioDrm
import nl.neerdael.milkbeat.plugin.AudioDrmScheme
import nl.neerdael.milkbeat.plugin.AudioStream
import org.junit.Test
import java.io.IOException

class PluginAudioDownloadsTest {
    private val clear = AudioStream("https://audio.example/track", "song", "aac", "audio/mp4")

    @Test
    fun `clear progressive audio remains downloadable`() {
        requireDownloadablePluginAudio(clear)
    }

    @Test
    fun `playlist and protected bytes cannot be marked as an offline audio download`() {
        val drm = AudioDrm(AudioDrmScheme.WIDEVINE, "https://license.example/playback?token=secret")
        for (stream in listOf(
            clear.copy(mimeType = "application/x-mpegURL"),
            clear.copy(mimeType = "application/vnd.apple.mpegurl; charset=utf-8"),
            clear.copy(drm = drm),
            clear.copy(
                cipher =
                    nl.neerdael.milkbeat.plugin
                        .AudioCipher(nl.neerdael.milkbeat.plugin.AudioCipherScheme.BF_CBC_STRIPE, "00".repeat(16)),
            ),
            clear.copy(mimeType = "application/x-server-abr"),
            clear.copy(
                serverAbr =
                    nl.neerdael.milkbeat.plugin.ServerAbrPlayback(
                        "https://cdn.example/post",
                        "song",
                        "fixture",
                        nl.neerdael.milkbeat.plugin
                            .ServerAbrClientInfo(7, "fixture"),
                        emptyList(),
                    ),
            ),
        )) {
            val failure = runCatching { requireDownloadablePluginAudio(stream) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(IOException::class.java)
            assertThat(failure!!.message).doesNotContain("secret")
        }
    }
}
