package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.RadioRequest
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

/** A queue that does not come from YouTube gets YouTube's radio for its first track. */
class OtherProviderRadioTest {
    private val spotifyTrack =
        TrackDescriptor(
            EntityRef(EntityKind.TRACK, "sp-1"),
            "Rakata (Goom Gum Remix)",
            artists = listOf(ArtistCredit("mr.Basic")),
            durationMs = 420000,
            ids = mapOf("sp" to "sp-1", "isrc" to "NL0000000001"),
        )
    private val matched = spotifyTrack.copy(ref = EntityRef(EntityKind.TRACK, "yt-rakata"), ids = mapOf("ytm" to "yt-rakata"))
    private val suggestion = matched.copy(ref = EntityRef(EntityKind.TRACK, "next-song"), title = "Next song")
    private val spotifyNext = spotifyTrack.copy(ref = EntityRef(EntityKind.TRACK, "sp-2"), title = "Spotify next")
    private val host = mockk<PluginHost>()
    private val registry = mockk<PluginRegistry>()
    private val audio = PluginAudio(host, registry, PluginTrackMatcher(host, MemoryTrackMatches(), registry), PluginAccounts(host))
    private val radio = PluginRadio(host, registry, audio)
    private val youtube =
        plugin(
            "youtube",
            Roles(
                metadata = MetadataRole(setOf(MetadataSurface.RADIO), setOf(EntityKind.TRACK), idSpace = "ytm"),
                audio = AudioRole(setOf("ytm"), match = true, radio = true),
            ),
        )
    private val spotify =
        plugin("spotify", Roles(metadata = MetadataRole(setOf(MetadataSurface.RADIO), setOf(EntityKind.TRACK), idSpace = "sp")))
    private val state = MutableStateFlow(PluginRegistryState(listOf(spotify, youtube), ProviderSelection(audio = listOf("youtube"))))

    init {
        coEvery { host.playbackLease(any()) } answers { PluginPlaybackLease(host, { 0L }, {}, {}) }
        every { registry.state } returns state
        coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(matched))
        coEvery { host.call("youtube", PluginOperations.audioRadio, any()) } returns TrackList(listOf(matched, suggestion))
        coEvery { host.call("youtube", PluginOperations.radio, any()) } returns TrackList(listOf(suggestion))
        coEvery { host.call("spotify", PluginOperations.radio, any()) } returns TrackList(listOf(spotifyNext))
    }

    @Test
    fun `another provider's track gets YouTube's radio for its match, not its own provider's`() =
        runTest {
            val page = radio.page(spotifyTrack.ref, spotifyTrack)

            assertThat(page?.pluginId).isEqualTo("youtube")
            assertThat(page?.seed?.providerId).isEqualTo("yt-rakata")
            assertThat(page?.tracks?.tracks).containsExactly(matched, suggestion).inOrder()
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.matchAudio, match { it.track.ids["isrc"] == "NL0000000001" }) }
            coVerify(exactly = 0) { host.call("spotify", PluginOperations.radio, any()) }
            coVerify(exactly = 0) { host.call(any(), PluginOperations.resolveAudio, any()) }
        }

    @Test
    fun `a provider-scoped playback id still gets YouTube's radio for another provider's track`() =
        runTest {
            val seed = EntityRef(EntityKind.TRACK, ProviderEntityReference.encode("spotify", spotifyTrack.ref))

            val page = radio.page(seed, spotifyTrack)

            assertThat(page?.pluginId).isEqualTo("youtube")
            assertThat(page?.seed?.providerId).isEqualTo("yt-rakata")
            coVerify(exactly = 0) { host.call("spotify", PluginOperations.radio, any()) }
        }

    @Test
    fun `a track YouTube cannot match keeps its own provider's radio`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(emptyList())

            val page = radio.page(spotifyTrack.ref, spotifyTrack)

            assertThat(page?.pluginId).isEqualTo("spotify")
            assertThat(page?.tracks?.tracks).containsExactly(spotifyNext)
        }

    @Test
    fun `a YouTube track keeps YouTube's own radio without matching`() =
        runTest {
            val page = radio.page(matched.ref, matched)

            assertThat(page?.pluginId).isEqualTo("youtube")
            assertThat(page?.fromAudio).isFalse()
            coVerify(exactly = 1) { host.call("youtube", PluginOperations.radio, RadioRequest(matched.ref)) }
            coVerify(exactly = 0) { host.call(any(), PluginOperations.matchAudio, any()) }
        }

    private fun plugin(
        id: String,
        roles: Roles,
    ) = InstalledPlugin(
        PluginManifest(1, ApiRange(1, 5), id, id, "1.0", 1, roles = roles),
        "signer",
        "test://$id",
        0,
        emptyList(),
        emptyList(),
    )
}
