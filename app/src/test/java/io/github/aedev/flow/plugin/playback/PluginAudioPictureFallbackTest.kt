package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.*
import org.junit.Test

class PluginAudioPictureFallbackTest : PluginAudioFixture() {
    private val alternative = matchTrack("alternative-recording", "alternative")
    private val picture =
        stream.copy(
            video = MediaFormat("picture", FormatType.VIDEO, "https://media.example/picture", "video/mp4", codecs = "avc1"),
        )
    private val strictHls =
        stream.copy(
            url = "https://media.example/master.m3u8",
            mimeType = "application/x-mpegURL",
            requireAudioOnlyHls = true,
        )
    private val audioNative =
        stream.copy(
            url = "https://media.example/sabr",
            mimeType = "application/x-server-abr",
            serverAbr =
                ServerAbrPlayback(
                    "https://media.example/sabr",
                    candidate.ref.providerId,
                    "AQI",
                    ServerAbrClientInfo(7, "fixture"),
                    listOf(ServerAbrFormat(MediaFormat("251", FormatType.AUDIO, "", "audio/webm", codecs = "opus"), 251, "100")),
                    durationMs = 120000,
                ),
        )

    init {
        val first =
            plugin.copy(
                grantedNetwork = listOf("media.example"),
                manifest = plugin.manifest.copy(roles = Roles(audio = AudioRole(setOf("youtube"), match = true, musicVideo = true))),
            )
        val second =
            plugin.copy(
                grantedNetwork = listOf("media.example"),
                manifest =
                    plugin.manifest.copy(
                        id = "alternative",
                        roles = Roles(audio = AudioRole(setOf("alternative"), match = true, musicVideo = true)),
                    ),
            )
        every { registry.state } returns
            MutableStateFlow(PluginRegistryState(listOf(first, second), ProviderSelection(audio = listOf("youtube", "alternative"))))
        coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
        coEvery { host.call("alternative", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(alternative))
        coEvery { host.call("alternative", PluginOperations.resolveAudio, any()) } returns picture
    }

    @Test
    fun `initial strict HLS picture absence tries the later capable provider`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns strictHls
            val accepted = audio.resolve(original, PictureLimits(1080, listOf("avc1")))
            assertThat(accepted.pluginId).isEqualTo("alternative")
            assertThat(accepted.track).isEqualTo(alternative)
            assertThat(accepted.withPicture).isTrue()
            coVerify(exactly = 1) { host.call("alternative", PluginOperations.resolveAudio, match { it.video }) }
        }

    @Test
    fun `initial audio only SABR picture absence tries the later capable provider`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns audioNative
            assertThat(audio.resolve(original, PictureLimits(1080, listOf("avc1"))).pluginId).isEqualTo("alternative")
            coVerify(exactly = 1) { host.call("alternative", PluginOperations.resolveAudio, match { it.video }) }
        }

    @Test
    fun `picture unavailable surfaces only after all providers and retains song fallback`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns strictHls
            coEvery { host.call("alternative", PluginOperations.resolveAudio, any()) } returns strictHls
            val failure = runCatching { audio.resolve(original, PictureLimits(1080, listOf("avc1"))) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PictureUnavailable::class.java)
            coVerify(exactly = 1) { host.call("alternative", PluginOperations.resolveAudio, match { it.video }) }
            val song = audio.resolve(original, null)
            assertThat(song.pluginId).isEqualTo("youtube")
            assertThat(song.track).isEqualTo(candidate)
            assertThat(song.withPicture).isFalse()
        }
}
