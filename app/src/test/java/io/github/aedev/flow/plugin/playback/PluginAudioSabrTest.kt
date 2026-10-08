package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.*
import org.junit.Test

class PluginAudioSabrTest : PluginAudioFixture() {
    private val presentation =
        ServerAbrPlayback(
            "https://cdn.example/sabr",
            candidate.ref.providerId,
            "dXBzdHJlYW0=",
            ServerAbrClientInfo(7, "fixture"),
            listOf(ServerAbrFormat(MediaFormat("251", FormatType.AUDIO, "", "audio/webm", codecs = "opus"), 251, "123")),
        )

    private fun native(withPicture: Boolean = false): AudioStream {
        val formats =
            if (withPicture) {
                presentation.formats +
                    ServerAbrFormat(
                        MediaFormat("399", FormatType.VIDEO, "", "video/mp4", codecs = "av01.0.12M.08", width = 3840, height = 2160),
                        399,
                        "124",
                    )
            } else {
                presentation.formats
            }
        return stream.copy(url = presentation.url, mimeType = "application/x-server-abr", serverAbr = presentation.copy(formats = formats))
    }

    init {
        every { registry.state } returns
            MutableStateFlow(
                PluginRegistryState(
                    listOf(
                        plugin.copy(
                            grantedNetwork = listOf("cdn.example"),
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
        coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns native()
    }

    @Test
    fun `protocol recovery keeps the accepted match and opaque context and coalesces resolution`() =
        runTest {
            val accepted = audio.resolve(original, null, playbackId = "catalog-id")
            audio.failed("catalog-id", accepted.stream.url, null, "opaque-context", ServerAbrFailure.PLAYBACK_CONTEXT_RELOAD)
            val request = slot<ResolveAudioRequest>()
            coEvery { host.call("youtube", PluginOperations.resolveAudio, capture(request)) } returns native()
            val refreshed = audio.resolve(original, null, playbackId = "catalog-id")
            audio.resolve(original, null, playbackId = "catalog-id")
            assertThat(refreshed.track.ref).isEqualTo(accepted.track.ref)
            assertThat(request.captured.failure!!.reloadPlaybackContext).isEqualTo("opaque-context")
            assertThat(request.captured.failure!!.status).isNull()
            coVerify(exactly = 1) {
                host.call(
                    "youtube",
                    PluginOperations.resolveAudio,
                    match {
                        it.failure?.serverAbrFailure ==
                            ServerAbrFailure.PLAYBACK_CONTEXT_RELOAD
                    },
                )
            }
        }

    @Test
    fun `SABR picture-only descriptor needs no direct picture URL`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns native(true)
            assertThat(audio.resolve(original, PictureLimits(2160, listOf("av1"))).stream.video).isNull()
        }

    @Test
    fun `ordinary same-account and profile refresh leave the accepted source bound`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.account, Unit) } returns
                nl.neerdael.milkbeat.catalog.ProviderAccount
                    .SignedIn("listener", name = "First")
            accounts.refresh("youtube")
            val accepted = audio.resolve(original, null)
            coEvery { host.call("youtube", PluginOperations.account, Unit) } returns
                nl.neerdael.milkbeat.catalog.ProviderAccount
                    .SignedIn("listener", name = "Updated")
            accounts.refresh("youtube")
            assertThat(runCatching { audio.verifyBound(accepted) }.isSuccess).isTrue()
        }

    @Test
    fun `bound refresh propagates cancellation without fallback or a new match`() =
        runTest {
            val accepted = audio.resolve(original, null)
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } throws CancellationException("cancelled")
            assertThat(runCatching { audio.refreshBound(accepted) }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        }
}
