package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.Test

class PreparedAudioAccountScopeTest : PluginAudioFixture() {
    @Test
    fun `prepared direct playback is not interrupted by another provider account refresh`() =
        runTest {
            val sound = MediaFormat(stream.renditionId, FormatType.AUDIO, stream.url, stream.mimeType, codecs = "mp4a.40.2")
            val source = plugin.copy(grantedNetwork = listOf("example.invalid"))
            val selected = ProviderSelection(audio = listOf("youtube"))
            every { registry.state } returns MutableStateFlow(PluginRegistryState(listOf(source), selected))
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns stream.copy(audioFormat = sound)
            val accepted = audio.resolve(original, null)
            coEvery { host.call("soundcloud", PluginOperations.account, Unit) } returns ProviderAccount.Anonymous
            accounts.refresh("soundcloud")
            assertThat(runCatching { audio.verifyBound(accepted) }.isSuccess).isTrue()
        }
}
