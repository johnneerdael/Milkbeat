package io.github.aedev.flow.plugin.playback

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.Permissions
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class PluginRangeRequestsTest {
    private val stream = AudioStream("https://media.example/song.mp3", "song", "mp3", "audio/mpeg")

    private val unset = C.LENGTH_UNSET.toLong()
    private val legacy = PluginRangePolicy.Legacy

    @Test
    fun `a stream is fetched in one request unless its plugin asks for ranges`() {
        assertThat(pluginRequestLength(unset, 524_288, picture = false, policy = null)).isEqualTo(unset)
        assertThat(pluginRequestLength(unset, 1, picture = true, policy = null)).isEqualTo(unset)
        assertThat(pluginRequestLength(4_096, 0, picture = false, policy = null)).isEqualTo(4_096)
    }

    @Test
    fun `a declared size never bounds the open, which the network transport splits`() {
        val declared = PluginRangePolicy.Declared(1_000_000)
        assertThat(pluginRequestLength(unset, 0, picture = false, policy = declared)).isEqualTo(unset)
        assertThat(pluginRequestLength(unset, 1, picture = true, policy = declared)).isEqualTo(unset)
        assertThat(pluginRequestLength(8_000_000, 0, picture = false, policy = declared)).isEqualTo(8_000_000)
    }

    @Test
    fun `legacy ranges keep their former shape`() {
        assertThat(pluginRequestLength(unset, 0, picture = false, policy = legacy)).isEqualTo(LEGACY_RANGE_REQUEST_BYTES)
        assertThat(pluginRequestLength(unset, 0, picture = true, policy = legacy)).isEqualTo(LEGACY_RANGE_REQUEST_BYTES)
        assertThat(pluginRequestLength(unset, 1, picture = true, policy = legacy)).isEqualTo(4L * 1024 * 1024)
        assertThat(pluginRequestLength(8_000_000, 0, picture = false, policy = legacy)).isEqualTo(8_000_000)
    }

    @Test
    fun `only plugins written before API 10 inherit the former host ranges`() {
        assertThat(stream.rangePolicyFor(9)).isEqualTo(legacy)
        assertThat(stream.rangePolicyFor(null)).isEqualTo(legacy)
        assertThat(stream.rangePolicyFor(10)).isNull()
        assertThat(stream.copy(rangeRequestBytes = 2_048).rangePolicyFor(9)).isEqualTo(PluginRangePolicy.Declared(2_048))
    }

    @Test
    fun `resolution and bound refresh apply the resolving plugin's own range policy`() =
        runTest {
            val older = Fixture(apiTarget = 9)
            val legacyAudio = older.audio.resolve(older.track, null)
            assertThat(legacyAudio.rangePolicy).isEqualTo(legacy)
            assertThat(
                older.audio
                    .refreshBound(legacyAudio)
                    .rangePolicy,
            ).isEqualTo(legacy)

            val current = Fixture(apiTarget = 10)
            val currentAudio = current.audio.resolve(current.track, null)
            assertThat(currentAudio.rangePolicy).isNull()
            assertThat(legacyAudio.stream).isEqualTo(stream)
            assertThat(
                current.audio
                    .refreshBound(currentAudio)
                    .rangePolicy,
            ).isNull()
        }

    @Test
    fun `a non-positive range request size is refused`() =
        runTest {
            val fixture = Fixture(apiTarget = 10, offered = stream.copy(rangeRequestBytes = 0))
            val failure = runCatching { fixture.audio.resolve(fixture.track, null) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PluginCallException::class.java)
            assertThat((failure as PluginCallException).error.code).isEqualTo(PluginErrorCode.UNSUPPORTED)
        }

    private inner class Fixture(
        apiTarget: Int,
        offered: AudioStream = stream,
    ) {
        val track = matchTrack("song", "deezer")
        private val host = mockk<PluginHost>()
        private val registry = mockk<PluginRegistry>()
        private val state =
            MutableStateFlow(
                PluginRegistryState(
                    listOf(
                        InstalledPlugin(
                            PluginManifest(
                                1,
                                ApiRange(apiTarget, apiTarget),
                                "deezer",
                                "Deezer",
                                "1.0",
                                1,
                                roles = Roles(audio = AudioRole(setOf("deezer"))),
                                permissions = Permissions(network = listOf("media.example")),
                            ),
                            "signer",
                            "https://fixture/plugin",
                            0,
                            listOf("media.example"),
                            emptyList(),
                        ),
                    ),
                    ProviderSelection(audio = listOf("deezer")),
                ),
            )
        val audio =
            PluginAudio(
                host,
                registry,
                PluginTrackMatcher(host, MemoryTrackMatches()),
                PluginAccounts(host, CoroutineScope(StandardTestDispatcher()), { 0L }),
            )

        init {
            coEvery { host.playbackLease(any()) } answers { PluginPlaybackLease(host, { 0L }, {}, {}) }
            every { registry.state } returns state
            coEvery { host.call("deezer", PluginOperations.resolveAudio, any()) } returns offered
        }
    }
}
