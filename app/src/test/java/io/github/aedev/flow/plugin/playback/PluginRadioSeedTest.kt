package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.RadioRequest
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

/** Radio needs no chosen metadata plugin: the seed says whose it is, or the track's ids do. */
class PluginRadioSeedTest {
    private val host = mockk<PluginHost>()
    private val registry = mockk<PluginRegistry>()
    private val radio = PluginRadio(host, registry, mockk())
    private val requests = mutableListOf<Pair<String, RadioRequest>>()
    private val station = TrackDescriptor(EntityRef(EntityKind.TRACK, "next"), "Next")

    init {
        every { registry.state } returns MutableStateFlow(PluginRegistryState(listOf(plugin("spotify", "sp"), plugin("beatport", "bp"))))
        coEvery { host.call(any(), PluginOperations.radio, any()) } answers {
            requests += firstArg<String>() to thirdArg<RadioRequest>()
            TrackList(listOf(station))
        }
    }

    @Test
    fun `a station encoded with its provider plays that provider's radio`() =
        runTest {
            val seed = EntityRef(EntityKind.PLAYLIST, ProviderEntityReference.encode("beatport", EntityRef(EntityKind.PLAYLIST, "st")))

            val page = radio.page(seed)

            assertThat(requests).containsExactly("beatport" to RadioRequest(EntityRef(EntityKind.PLAYLIST, "st")))
            assertThat(page?.pluginId).isEqualTo("beatport")
        }

    @Test
    fun `a track plays the radio of the plugin whose ids it carries`() =
        runTest {
            val track = TrackDescriptor(EntityRef(EntityKind.TRACK, "t"), "Song", ids = mapOf("sp" to "sp-1"))

            val page = radio.page(track.ref, track)

            assertThat(requests).containsExactly("spotify" to RadioRequest(EntityRef(EntityKind.TRACK, "sp-1")))
            assertThat(page?.pluginId).isEqualTo("spotify")
        }

    @Test
    fun `a track known to several plugins plays the radio of the plugin that described it`() =
        runTest {
            val track = TrackDescriptor(EntityRef(EntityKind.TRACK, "bp-1"), "Song", ids = mapOf("sp" to "sp-1", "bp" to "bp-1"))

            val page = radio.page(track.ref, track)

            assertThat(requests).containsExactly("beatport" to RadioRequest(EntityRef(EntityKind.TRACK, "bp-1")))
            assertThat(page?.pluginId).isEqualTo("beatport")
        }

    private fun plugin(
        id: String,
        idSpace: String,
    ) = InstalledPlugin(
        PluginManifest(
            1,
            ApiRange(1, 5),
            id,
            id,
            "1.0",
            1,
            roles = Roles(metadata = MetadataRole(setOf(MetadataSurface.RADIO), setOf(EntityKind.TRACK), idSpace = idSpace)),
        ),
        "signer",
        "https://example.test/$id",
        0,
        emptyList(),
        emptyList(),
    )
}
