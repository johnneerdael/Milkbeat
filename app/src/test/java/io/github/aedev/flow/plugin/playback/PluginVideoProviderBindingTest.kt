package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.mockk.coEvery
import io.mockk.every
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ResolveVideoRequest
import org.junit.Test
import java.io.IOException

class PluginVideoProviderBindingTest : PluginAudioFixture() {
    private val provider = PluginVideoProvider(host, registry, accounts)
    private val state = MutableStateFlow(PluginRegistryState(listOf(plugin), ProviderSelection(video = "youtube")))
    private val request = ResolveVideoRequest(videoRef("recording"))

    init {
        every { registry.state } returns state
        coEvery { host.call("youtube", PluginOperations.account, Unit) } returns ProviderAccount.Anonymous
        coEvery { host.call("youtube", PluginOperations.resolveVideo, request) } returns PluginVideoStreamsTest.playback()
    }

    @Test
    fun `unrelated account and registry updates keep accepted video ownership`() =
        runTest {
            val accepted = provider.preparePlaybackContext("youtube")
            coEvery { host.call("soundcloud", PluginOperations.account, Unit) } returns ProviderAccount.Anonymous
            accounts.refresh("soundcloud")
            val unrelated = plugin.copy(manifest = plugin.manifest.copy(id = "soundcloud"))
            state.value = state.value.copy(plugins = state.value.plugins + unrelated)
            assertThat(
                provider
                    .resolveBound("youtube", accepted, request)
                    .details.entity.providerId,
            ).isNotEmpty()
        }

    @Test
    fun `same-provider ABA and grant changes invalidate accepted video ownership`() =
        runTest {
            val accepted = provider.preparePlaybackContext("youtube")
            coEvery { host.call("youtube", PluginOperations.signOut, Unit) } returns Unit
            accounts.signOut("youtube")
            val retired = runCatching { provider.resolveBound("youtube", accepted, request) }.exceptionOrNull()
            assertThat(retired).isInstanceOf(IOException::class.java)
            val next = provider.preparePlaybackContext("youtube")
            state.value = state.value.copy(plugins = listOf(plugin.copy(grantedNetwork = listOf("new.example"))))
            val revoked = runCatching { provider.resolveBound("youtube", next, request) }.exceptionOrNull()
            assertThat(revoked).isInstanceOf(IOException::class.java)
        }
}
