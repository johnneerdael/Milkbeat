package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
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
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

class LocalSongRadioTest {
    private val local =
        MusicTrack(
            videoId = "local_42",
            title = "Rakata (Goom Gum Remix)",
            artist = "mr.Basic",
            artists = listOf(MusicArtist("mr.Basic", "local:artist:mr.Basic")),
            thumbnailUrl = "",
            duration = 420,
            album = "Rakata",
        )
    private val matched =
        TrackDescriptor(
            EntityRef(EntityKind.TRACK, "yt-rakata"),
            local.title,
            artists = listOf(ArtistCredit("mr.Basic")),
            durationMs = 420000,
            ids = mapOf("ytm" to "yt-rakata"),
        )
    private val suggestion = matched.copy(ref = EntityRef(EntityKind.TRACK, "next-song"), title = "Next song")
    private val host = mockk<PluginHost>()
    private val registry = mockk<PluginRegistry>()
    private val audio = PluginAudio(host, registry, PluginTrackMatcher(host, MemoryTrackMatches()), PluginAccounts(host))
    private val radio = PluginRadio(host, registry, audio)
    private val youtube =
        InstalledPlugin(
            PluginManifest(
                1,
                ApiRange(1, 2),
                "youtube",
                "YouTube Music",
                "1.0",
                1,
                roles = Roles(audio = AudioRole(setOf("ytm"), match = true, radio = true)),
            ),
            "signer",
            "test://youtube",
            0,
            emptyList(),
            emptyList(),
        )
    private val state = MutableStateFlow(PluginRegistryState(listOf(youtube), ProviderSelection(audio = listOf("youtube"))))

    init {
        coEvery { host.playbackLease(any()) } answers { PluginPlaybackLease(host, { 0L }, {}, {}) }
        every { registry.state } returns state
        coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(listOf(matched))
        coEvery { host.call("youtube", PluginOperations.audioRadio, any()) } returns TrackList(listOf(matched, suggestion))
    }

    @Test
    fun `local metadata matches a YouTube seed without resolving streaming audio`() =
        runTest {
            val descriptor = MusicVideoItems.descriptor(local)
            val page = radio.page(descriptor.ref, descriptor)

            assertThat(page?.seed?.providerId).isEqualTo("yt-rakata")
            assertThat(page?.tracks?.tracks).containsExactly(matched, suggestion).inOrder()
            assertThat(descriptor.ids).doesNotContainKey("ytm")
            assertThat(descriptor.artists.map { it.name }).containsExactly("mr.Basic")
            assertThat(descriptor.durationMs).isEqualTo(420000)
            assertThat(local.videoId).isEqualTo("local_42")
            coVerify(exactly = 1) {
                host.call(
                    "youtube",
                    PluginOperations.matchAudio,
                    match {
                        it.track.title == local.title &&
                            it.track.artists
                                .first()
                                .name == "mr.Basic"
                    },
                )
            }
            coVerify(exactly = 0) { host.call(any(), PluginOperations.resolveAudio, any()) }
        }

    @Test
    fun `local radio uses YouTube even when the selected streaming provider is different`() =
        runTest {
            state.value = state.value.copy(selection = ProviderSelection(audio = listOf("beatport")))
            val descriptor = MusicVideoItems.descriptor(local)

            assertThat(radio.page(descriptor.ref, descriptor)?.pluginId).isEqualTo("youtube")
        }

    @Test
    fun `no confident match leaves radio empty`() =
        runTest {
            coEvery { host.call("youtube", PluginOperations.matchAudio, any()) } returns AudioMatches(emptyList())
            val descriptor = MusicVideoItems.descriptor(local)

            assertThat(radio.page(descriptor.ref, descriptor)).isNull()
            coVerify(exactly = 0) { host.call(any(), PluginOperations.audioRadio, any()) }
            coVerify(exactly = 0) { host.call(any(), PluginOperations.resolveAudio, any()) }
        }

    @Test
    fun `disabled YouTube is not called for local radio`() =
        runTest {
            state.value = state.value.copy(plugins = listOf(youtube.copy(enabled = false)))
            val descriptor = MusicVideoItems.descriptor(local)

            assertThat(radio.page(descriptor.ref, descriptor)).isNull()
            coVerify(exactly = 0) { host.call(any(), PluginOperations.matchAudio, any()) }
        }
}
