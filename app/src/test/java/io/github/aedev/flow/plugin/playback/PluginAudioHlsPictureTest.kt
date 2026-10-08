package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ServerAbrClientInfo
import nl.neerdael.milkbeat.plugin.ServerAbrFormat
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import org.junit.Test

class PluginAudioHlsPictureTest : PluginAudioFixture() {
    private val hls: AudioStream = stream.copy(url = "https://media.example/master.m3u8", mimeType = "application/x-mpegURL")

    init {
        every { registry.state } returns
            MutableStateFlow(
                PluginRegistryState(
                    listOf(
                        plugin.copy(
                            grantedNetwork = listOf("media.example"),
                            manifest =
                                plugin.manifest.copy(
                                    roles =
                                        plugin.manifest.roles.copy(
                                            audio =
                                                plugin.manifest.roles.audio!!
                                                    .copy(musicVideo = true),
                                        ),
                                ),
                        ),
                    ),
                    ProviderSelection(audio = listOf("youtube")),
                ),
            )
    }

    @Test
    fun `a native picture remains valid when its unused HLS backup requires audio only`() =
        runTest {
            val native =
                ServerAbrPlayback(
                    "https://media.example/sabr",
                    candidate.ref.providerId,
                    "fixture",
                    ServerAbrClientInfo(7, "fixture"),
                    listOf(
                        ServerAbrFormat(MediaFormat("251", FormatType.AUDIO, "", "audio/webm", codecs = "opus"), 251, "100"),
                        ServerAbrFormat(MediaFormat("399", FormatType.VIDEO, "", "video/mp4", codecs = "av01.0.12M.08"), 399, "101"),
                    ),
                    durationMs = 120000,
                )
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns
                hls.copy(requireAudioOnlyHls = true, serverAbr = native)
            val accepted = audio.resolve(original, PictureLimits(2160, listOf("av1")))
            assertThat(accepted.withPicture).isTrue()
            assertThat(accepted.stream.serverAbr).isEqualTo(native)
        }

    @Test
    fun `strict HLS cannot claim picture through a separate URL the HLS source never merges`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns
                hls.copy(
                    requireAudioOnlyHls = true,
                    video = MediaFormat("picture", FormatType.VIDEO, "https://media.example/picture", "video/mp4"),
                )
            assertThat(runCatching { audio.resolve(original, PictureLimits(2160, listOf("av1"))) }.isFailure).isTrue()
        }

    @Test
    fun `audio-only HLS flag does not affect a progressive picture source`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns
                stream.copy(
                    requireAudioOnlyHls = true,
                    video = MediaFormat("picture", FormatType.VIDEO, "https://media.example/picture", "video/mp4"),
                )
            assertThat(audio.resolve(original, PictureLimits(2160, listOf("av1"))).withPicture).isTrue()
        }

    @Test
    fun `strict audio HLS cannot be accepted as picture and remains playable as its song`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns
                hls.copy(requireAudioOnlyHls = true)
            val unavailable = runCatching { audio.resolve(original, PictureLimits(2160, listOf("av1"))) }.exceptionOrNull()
            assertThat(unavailable).isInstanceOf(PictureUnavailable::class.java)
            val song = audio.resolve(original, null)
            assertThat(song.track.ref).isEqualTo(candidate.ref)
            assertThat(song.withPicture).isFalse()
            assertThat(song.stream.requireAudioOnlyHls).isTrue()
        }

    @Test
    fun `normal video HLS remains a valid accepted picture without a separate progressive URL`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns hls
            val picture = audio.resolve(original, PictureLimits(2160, listOf("av1")))
            assertThat(picture.withPicture).isTrue()
            assertThat(picture.stream.video).isNull()
        }

    @Test
    fun `bound picture refresh rejects a downgrade to strict audio HLS`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns hls
            val accepted = audio.resolve(original, PictureLimits(2160, listOf("av1")))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns hls.copy(requireAudioOnlyHls = true)
            assertThat(runCatching { audio.refreshBound(accepted) }.isFailure).isTrue()
        }
}
