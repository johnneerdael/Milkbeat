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

    @Test
    fun `a stream is cut into ranges only when its plugin asks`() {
        assertThat(pluginRequestLength(C.LENGTH_UNSET.toLong(), 524_288, picture = false, rangeRequestBytes = null))
            .isEqualTo(C.LENGTH_UNSET.toLong())
        assertThat(pluginRequestLength(C.LENGTH_UNSET.toLong(), 0, picture = false, rangeRequestBytes = 1_000_000))
            .isEqualTo(1_000_000)
        assertThat(pluginRequestLength(4_096, 0, picture = false, rangeRequestBytes = 1_000_000)).isEqualTo(4_096)
    }

    @Test
    fun `a picture keeps an audio-sized first range and wider ranges after it`() {
        assertThat(pluginRequestLength(C.LENGTH_UNSET.toLong(), 0, picture = true, rangeRequestBytes = LEGACY_RANGE_REQUEST_BYTES))
            .isEqualTo(LEGACY_RANGE_REQUEST_BYTES)
        assertThat(pluginRequestLength(C.LENGTH_UNSET.toLong(), 1, picture = true, rangeRequestBytes = LEGACY_RANGE_REQUEST_BYTES))
            .isEqualTo(4L * 1024 * 1024)
        assertThat(pluginRequestLength(C.LENGTH_UNSET.toLong(), 1, picture = true, rangeRequestBytes = null))
            .isEqualTo(C.LENGTH_UNSET.toLong())
    }

    @Test
    fun `only plugins written before API 10 inherit the former host ranges`() {
        assertThat(stream.rangeRequestBytesFor(9)).isEqualTo(LEGACY_RANGE_REQUEST_BYTES)
        assertThat(stream.rangeRequestBytesFor(null)).isEqualTo(LEGACY_RANGE_REQUEST_BYTES)
        assertThat(stream.rangeRequestBytesFor(10)).isNull()
        assertThat(stream.copy(rangeRequestBytes = 2_048).rangeRequestBytesFor(9)).isEqualTo(2_048)
    }

    @Test
    fun `resolution and bound refresh apply the resolving plugin's own range policy`() =
        runTest {
            val legacy = Fixture(apiTarget = 9)
            val legacyAudio = legacy.audio.resolve(legacy.track, null)
            assertThat(legacyAudio.rangeRequestBytes).isEqualTo(LEGACY_RANGE_REQUEST_BYTES)
            assertThat(
                legacy.audio
                    .refreshBound(legacyAudio)
                    .rangeRequestBytes,
            ).isEqualTo(LEGACY_RANGE_REQUEST_BYTES)

            val current = Fixture(apiTarget = 10)
            val currentAudio = current.audio.resolve(current.track, null)
            assertThat(currentAudio.rangeRequestBytes).isNull()
            assertThat(legacyAudio.stream).isEqualTo(stream)
            assertThat(
                current.audio
                    .refreshBound(currentAudio)
                    .rangeRequestBytes,
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
