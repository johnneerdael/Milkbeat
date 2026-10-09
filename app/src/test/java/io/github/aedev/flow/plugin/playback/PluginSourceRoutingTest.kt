package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginSourceRoutingTest : PluginAudioFixture() {
    private fun provider(id: String): InstalledPlugin =
        plugin.copy(manifest = plugin.manifest.copy(id = id, roles = Roles(audio = AudioRole(setOf(id), match = true))))

    private fun select(vararg providers: InstalledPlugin) {
        every { registry.state } returns
            MutableStateFlow(PluginRegistryState(providers.toList(), ProviderSelection(audio = providers.map { it.id })))
    }

    @Test
    fun `YouTube source uses known id before globally preferred matching provider`() =
        runTest {
            select(provider("beatport"), plugin)
            assertThat(audio.needsQueueMatching(candidate)).isFalse()
            assertThat(audio.resolve(candidate, null).pluginId).isEqualTo("youtube")
            coVerify(exactly = 0) { host.call(any(), PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `SoundCloud and Beatport source use their own streams before YouTube search`() =
        runTest {
            for (id in listOf("soundcloud", "beatport")) {
                select(plugin, provider(id))
                coEvery { host.call(id, PluginOperations.resolveAudio, any()) } returns stream
                val track = matchTrack("$id-native", id)
                assertThat(audio.needsQueueMatching(track)).isFalse()
                assertThat(audio.resolve(track, null).pluginId).isEqualTo(id)
            }
            coVerify(exactly = 0) { host.call(any(), PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `Spotify resolves faster playable provider without waiting for preferred search and cancels loser`() =
        runTest {
            select(plugin, provider("beatport"))
            var cancelled = false
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } coAnswers {
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            }
            val beatport = matchTrack("bp-match", "beatport")
            coEvery { host.call("beatport", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(beatport))
            coEvery { host.call("beatport", PluginOperations.resolveAudio, any()) } returns stream
            val pending = async { audio.resolve(original, null) }
            runCurrent()
            assertThat(pending.isCompleted).isTrue()
            assertThat(pending.await().track).isEqualTo(beatport)
            assertThat(cancelled).isTrue()
        }

    @Test
    fun `Spotify keeps waiting when fastest provider has wrong candidate or unplayable stream`() =
        runTest {
            select(plugin, provider("soundcloud"), provider("beatport"))
            val gate = CompletableDeferred<Unit>()
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } coAnswers {
                gate.await()
                AudioMatches(listOf(candidate))
            }
            val wrong = matchTrack("wrong", "soundcloud").copy(title = "A completely different song")
            coEvery { host.call("soundcloud", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(wrong))
            coEvery { host.call("beatport", PluginOperations.matchAudio, any()) } returns
                AudioMatches(listOf(matchTrack("unplayable", "beatport")))
            coEvery { host.call("beatport", PluginOperations.resolveAudio, any()) } throws
                PluginCallException("beatport", PluginError(PluginErrorCode.UNAVAILABLE, "No stream"))
            val pending = async { audio.resolve(original, null) }
            runCurrent()
            coVerify(atLeast = 1) { host.call("beatport", PluginOperations.resolveAudio, any()) }
            coVerify(exactly = 0) { host.call("soundcloud", PluginOperations.resolveAudio, any()) }
            assertThat(pending.isCompleted).isFalse()
            gate.complete(Unit)
            assertThat(pending.await().track).isEqualTo(candidate)
        }

    @Test
    fun `cancelling Spotify playback cancels every in flight provider`() =
        runTest {
            select(plugin, provider("beatport"))
            val started = mutableSetOf<String>()
            val cancelled = mutableSetOf<String>()
            for (id in listOf("youtube", "beatport")) {
                coEvery { host.call(id, PluginOperations.matchAudio, any()) } coAnswers {
                    started += id
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled += id
                    }
                }
            }
            val pending = async { audio.resolve(original, null) }
            runCurrent()
            assertThat(started).containsExactly("youtube", "beatport")
            pending.cancel()
            runCurrent()
            assertThat(cancelled).containsExactly("youtube", "beatport")
            assertThat(audio.current(original.ref.providerId)).isNull()
        }

    @Test
    fun `unavailable YouTube source never searches for a different recording`() =
        runTest {
            select(provider("beatport"), plugin)
            coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } throws
                PluginCallException("youtube", PluginError(PluginErrorCode.UNAVAILABLE, "Unavailable"))
            try {
                audio.resolve(candidate, null)
                throw AssertionError("Expected unavailable native recording")
            } catch (error: PluginCallException) {
                assertThat(error.pluginId).isEqualTo("youtube")
            }
            coVerify(exactly = 0) { host.call(any(), PluginOperations.matchAudio, any()) }
            coVerify(exactly = 0) { host.call("beatport", PluginOperations.resolveAudio, any()) }
        }

    @Test
    fun `Spotify only searches enabled configured providers`() =
        runTest {
            val disabled = provider("beatport").copy(enabled = false)
            val unconfigured = provider("soundcloud")
            every { registry.state } returns
                MutableStateFlow(
                    PluginRegistryState(listOf(plugin, disabled, unconfigured), ProviderSelection(audio = listOf("youtube", "beatport"))),
                )
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(candidate))
            assertThat(audio.resolve(original, null).pluginId).isEqualTo("youtube")
            coVerify(exactly = 0) { host.call("beatport", PluginOperations.matchAudio, any()) }
            coVerify(exactly = 0) { host.call("soundcloud", PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `Spotify provider searches stay bounded and queued providers cancel with playback`() =
        runTest {
            val providers = (1..8).map { provider("provider-$it") }
            select(*providers.toTypedArray())
            val started = mutableSetOf<String>()
            for (provider in providers) {
                coEvery { host.call(provider.id, PluginOperations.matchAudio, any()) } coAnswers {
                    started += provider.id
                    awaitCancellation()
                }
            }
            val pending = async { audio.resolve(original, null) }
            runCurrent()
            assertThat(started).hasSize(4)
            pending.cancel()
            runCurrent()
            assertThat(started).hasSize(4)
        }

    @Test
    fun `YouTube source ref takes precedence over an earlier alternate id space`() =
        runTest {
            val youtube = plugin.copy(manifest = plugin.manifest.copy(roles = Roles(audio = AudioRole(setOf("yt", "ytm"), match = true))))
            select(provider("beatport"), youtube)
            val source = candidate.copy(ids = linkedMapOf("ytm" to "alternate-song", "yt" to candidate.ref.providerId))
            assertThat(audio.resolve(source, null).track.ref).isEqualTo(source.ref)
            coVerify(exactly = 0) { host.call(any(), PluginOperations.matchAudio, any()) }
        }

    @Test
    fun `no configured audio providers does not request queue matching`() =
        runTest {
            select()
            assertThat(audio.needsQueueMatching(original)).isFalse()
        }
}
