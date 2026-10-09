package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PreparedAudioResolutionTest : PluginAudioFixture() {
    private val limits = PictureLimits(2160, listOf("vp9", "av1"))

    @Test
    fun `older video providers retry optional picture absence as audio without changing the match`() =
        runTest {
            val roles = Roles(audio = AudioRole(setOf("youtube"), match = true, musicVideo = true))
            val legacy = plugin.copy(manifest = plugin.manifest.copy(api = ApiRange(6, 6), roles = roles))
            val selected = ProviderSelection(audio = listOf("youtube"))
            every { registry.state } returns MutableStateFlow(PluginRegistryState(listOf(legacy), selected))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.video }) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "No picture"))
            val accepted = audio.resolve(original, null, preparePicture = limits)
            assertThat(accepted.stream).isEqualTo(stream)
            assertThat(accepted.withPicture).isFalse()
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.resolveAudio, match { it.video }) }
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.resolveAudio, match { !it.video }) }
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `repeated denied media advances to original audio instead of resolving YouTube forever`() =
        runTest {
            val roles = Roles(audio = AudioRole(setOf("youtube"), match = true, musicVideo = true))
            val videoProvider = plugin.copy(manifest = plugin.manifest.copy(api = ApiRange(7, 7), roles = roles))
            val source = plugin.copy(manifest = plugin.manifest.copy(id = "spotify", roles = Roles(audio = AudioRole(setOf("spotify")))))
            val selected = ProviderSelection(audio = listOf("youtube", "spotify"))
            every { registry.state } returns MutableStateFlow(PluginRegistryState(listOf(videoProvider, source), selected))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("spotify", PluginOperations.resolveAudio, any()) } returns stream
            audio.resolve(original, null, preparePicture = limits)
            audio.failed(original.ref.providerId, stream.url, 403)
            assertThat(audio.resolve(original, null, preparePicture = limits).pluginId).isEqualTo("youtube")
            audio.failed(original.ref.providerId, stream.url, 403)
            assertThat(audio.resolve(original, null, preparePicture = limits).pluginId).isEqualTo("spotify")
            coVerify(exactly = 2) { host.call("youtube", PluginOperations.resolveAudio, any()) }
            coVerify(exactly = 1) {
                host.call(
                    "spotify",
                    PluginOperations.resolveAudio,
                    match { !it.video && !it.prepareVideo && it.failure == null },
                )
            }
        }

    @Test
    fun `prepared picture metadata remains optional and reuses the audio resolution`() =
        runTest {
            val roles = Roles(audio = AudioRole(setOf("youtube"), match = true, musicVideo = true))
            val videoProvider = plugin.copy(manifest = plugin.manifest.copy(api = ApiRange(7, 7), roles = roles))
            val selected = ProviderSelection(audio = listOf("youtube"))
            every { registry.state } returns MutableStateFlow(PluginRegistryState(listOf(videoProvider), selected))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            val first = audio.resolve(original, null, preparePicture = limits)
            val second = audio.resolve(original, null, preparePicture = limits)
            assertThat(first).isSameInstanceAs(second)
            assertThat(first.stream.video).isNull()
            coVerify(exactly = 1) {
                host.call(
                    "youtube",
                    PluginOperations.resolveAudio,
                    match {
                        it.prepareVideo && !it.video && it.maxVideoHeight == 2160
                    },
                )
            }
        }

    @Test
    fun `picture preparation cannot exclude the original audio only provider after a YouTube failure`() =
        runTest {
            val roles = Roles(audio = AudioRole(setOf("youtube"), match = true, musicVideo = true))
            val videoProvider = plugin.copy(manifest = plugin.manifest.copy(api = ApiRange(7, 7), roles = roles))
            val source = plugin.copy(manifest = plugin.manifest.copy(id = "spotify", roles = Roles(audio = AudioRole(setOf("spotify")))))
            val selected = ProviderSelection(audio = listOf("youtube", "spotify"))
            every { registry.state } returns MutableStateFlow(PluginRegistryState(listOf(videoProvider, source), selected))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.NETWORK, "Media unavailable"))
            coEvery { host.call("spotify", PluginOperations.resolveAudio, any()) } returns stream
            val accepted = audio.resolve(original, null, preparePicture = limits)
            assertThat(accepted.pluginId).isEqualTo("spotify")
            assertThat(accepted.track).isEqualTo(original)
            coVerify(exactly = 1) {
                host.call(
                    "spotify",
                    PluginOperations.resolveAudio,
                    match {
                        !it.video && !it.prepareVideo && it.failure == null
                    },
                )
            }
        }
}
