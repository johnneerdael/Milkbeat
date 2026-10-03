package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.MusicPlaybackContext
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.catalog.toMusicTrack
import io.github.aedev.flow.plugin.mirror.MirrorKey
import io.github.aedev.flow.plugin.mirror.MirrorMatch
import io.github.aedev.flow.plugin.mirror.MirrorRecord
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorCoordinator
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.PluginJson
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MirrorPlaybackPreparationTest {
    @Test
    fun `playback uses the verified handoff and keeps its native collection context`() =
        runTest {
            val key = MirrorKey("spotify", "a", "youtube", "b", EntityRef(EntityKind.PLAYLIST, "source"))
            val descriptor = TrackDescriptor(EntityRef(EntityKind.TRACK, "spotify-song"), "Song")
            val native = descriptor.copy(ref = EntityRef(EntityKind.TRACK, "youtube-song"))
            val record =
                MirrorRecord(
                    key,
                    "my_playlist",
                    "r1",
                    listOf(descriptor),
                    listOf(MirrorMatch(0, descriptor, native)),
                    destination = EntityRef(EntityKind.PLAYLIST, "private-copy"),
                    ready = true,
                )
            val mirrors = mockk<PlaylistMirrorCoordinator>()
            coEvery { mirrors.selectedKey("spotify", key.source) } returns key
            coEvery { mirrors.prepareForPlayback(key, "my_playlist", any()) } returns record
            val plugin = mockk<InstalledPlugin>(relaxed = true)
            every { plugin.id } returns "youtube"
            every { plugin.enabled } returns true
            every { plugin.manifest.roles.metadata } returns MetadataRole(emptySet(), emptySet(), "ytm")
            val registry = mockk<PluginRegistry>()
            every { registry.state } returns MutableStateFlow(PluginRegistryState(listOf(plugin)))
            val sourceId = ProviderEntityReference.encode("spotify", key.source)
            val track = descriptor.toMusicTrack("spotify").copy(sourcePosition = 0)
            assertThat(musicSourceLabel(mockk(), "my_playlist", track)).isEqualTo("My Playlist")
            val result = MirrorPlaybackPreparation(mirrors, registry).prepare(track, listOf(track), sourceId, "my_playlist")
            assertThat(
                result.track.playbackContext?.radioCollectionId,
            ).isEqualTo(ProviderEntityReference.encode("youtube", record.destination!!))
            assertThat(result.track.playbackContext?.audioProviderId).isEqualTo("youtube")
            assertThat(MusicVideoItems.descriptor(result.track).ids["ytm"]).isEqualTo("youtube-song")
            coVerify(exactly = 1) { mirrors.prepareForPlayback(key, "my_playlist", any()) }
            coVerify(exactly = 0) { mirrors.prepare(any(), any(), any(), any()) }
        }

    @Test
    fun `pending preparation reports waiting before the prepared track is available`() =
        runTest {
            val key = MirrorKey("spotify", "a", "youtube", "b", EntityRef(EntityKind.PLAYLIST, "source"))
            val descriptor = TrackDescriptor(EntityRef(EntityKind.TRACK, "song"), "Song")
            val record =
                MirrorRecord(
                    key,
                    "Playlist",
                    "r1",
                    listOf(descriptor),
                    listOf(MirrorMatch(0, descriptor, descriptor.copy(ref = EntityRef(EntityKind.TRACK, "native")))),
                    destination = EntityRef(EntityKind.PLAYLIST, "private-copy"),
                    ready = true,
                )
            val finish = CompletableDeferred<MirrorRecord>()
            val mirrors = mockk<PlaylistMirrorCoordinator>()
            coEvery { mirrors.selectedKey("spotify", key.source) } returns key
            coEvery { mirrors.prepareForPlayback(key, "Playlist", any()) } coAnswers {
                thirdArg<() -> Unit>().invoke()
                finish.await()
            }
            val plugin = mockk<InstalledPlugin>(relaxed = true)
            every { plugin.id } returns "youtube"
            every { plugin.enabled } returns true
            every { plugin.manifest.roles.metadata } returns MetadataRole(emptySet(), emptySet(), "ytm")
            val registry = mockk<PluginRegistry>()
            every { registry.state } returns MutableStateFlow(PluginRegistryState(listOf(plugin)))
            val preparation = MirrorPlaybackPreparation(mirrors, registry)
            val track = descriptor.toMusicTrack("spotify").copy(sourcePosition = 0)
            var notices = 0
            val play =
                async {
                    preparation.prepare(track, listOf(track), ProviderEntityReference.encode("spotify", key.source), "Playlist") {
                        notices++
                    }
                }
            runCurrent()
            assertThat(notices).isEqualTo(1)
            assertThat(play.isCompleted).isFalse()
            finish.complete(record)
            assertThat(
                play
                    .await()
                    .track.playbackContext
                    ?.audioProviderId,
            ).isEqualTo("youtube")
            assertThat(notices).isEqualTo(1)
        }

    @Test
    fun `ordinary playback and disabled mirroring never report waiting`() =
        runTest {
            val entity = EntityRef(EntityKind.PLAYLIST, "source")
            val sourceId = ProviderEntityReference.encode("spotify", entity)
            val track = TrackDescriptor(EntityRef(EntityKind.TRACK, "song"), "Song").toMusicTrack("spotify")
            val mirrored = track.copy(playbackContext = MusicPlaybackContext(sourceId, "native", "youtube"))
            val mirrors = mockk<PlaylistMirrorCoordinator>()
            coEvery { mirrors.selectedKey("spotify", entity) } returns null
            val preparation = MirrorPlaybackPreparation(mirrors, mockk())
            var notices = 0
            val requests = listOf(track to null, track to "not-a-provider-reference", mirrored to sourceId, track to sourceId)
            for ((requested, source) in requests) {
                val result = preparation.prepare(requested, listOf(requested), source, "Playlist") { notices++ }
                assertThat(result.track).isSameInstanceAs(requested)
            }
            assertThat(notices).isEqualTo(0)
            coVerify(exactly = 0) { mirrors.prepareForPlayback(any(), any(), any()) }
        }

    @Test
    fun `explicit song radio clears mirrored collection context`() {
        val track =
            io.github.aedev.flow.data.music.model.MusicTrack(
                "song",
                "Song",
                "Artist",
                "",
                120,
                playbackContext = MusicPlaybackContext("source", "native", "youtube"),
                sourcePosition = 98,
            )
        val result = songRadioPlayback(track)
        assertThat(result.track.playbackContext).isNull()
        assertThat(result.track.sourcePosition).isNull()
        assertThat(result.queue).containsExactly(result.track)
    }

    @Test
    fun `partial UI pages still play the complete 122 occurrence mirror`() {
        val tracks = (0..121).map { TrackDescriptor(EntityRef(EntityKind.TRACK, "source$it"), "Song $it") }
        val key = MirrorKey("spotify", "a", "youtube", "b", EntityRef(EntityKind.PLAYLIST, "source"))
        val matches =
            tracks.mapIndexed {
                index,
                track,
                ->
                MirrorMatch(index, track, track.copy(ref = track.ref.copy(providerId = "native$index")))
            }
        val record = MirrorRecord(key, "Playlist", "r1", tracks, matches, ready = true)
        val queue = tracks.take(50).map { it.toMusicTrack("spotify") }
        val result = prepareMirrorPlayback(record, queue[0], queue, "ytm", MusicPlaybackContext("source", "native", "youtube"))
        assertThat(result.queue).hasSize(122)
    }

    @Test
    fun `later duplicate occurrence stays selected`() {
        val tracks = listOf("A", "B", "A", "C").map { TrackDescriptor(EntityRef(EntityKind.TRACK, it), it) }
        val key = MirrorKey("spotify", "a", "youtube", "b", EntityRef(EntityKind.PLAYLIST, "source"))
        val matches =
            tracks.mapIndexed {
                index,
                track,
                ->
                MirrorMatch(index, track, track.copy(ref = track.ref.copy(providerId = "native${track.title}")))
            }
        val record = MirrorRecord(key, "Playlist", "r1", tracks, matches, ready = true)
        val queue = tracks.mapIndexed { index, track -> track.toMusicTrack("spotify").copy(sourcePosition = index) }
        val result = prepareMirrorPlayback(record, queue[2], queue, "ytm", MusicPlaybackContext("source", "native", "youtube"))
        assertThat(result.startIndex).isEqualTo(2)
        assertThat(result.track.sourcePosition).isEqualTo(2)
    }

    @Test
    fun `prepared queue retains source artwork and identity and starts at next available match`() {
        val tracks =
            (0..3).map {
                TrackDescriptor(EntityRef(EntityKind.TRACK, "source$it"), "Source $it", artwork = Artwork("https://source.test/$it"))
            }
        val key = MirrorKey("spotify", "a", "youtube", "b", EntityRef(EntityKind.PLAYLIST, "source"))
        val matches = listOf(0, 2, 3).map { MirrorMatch(it, tracks[it], tracks[it].copy(ref = EntityRef(EntityKind.TRACK, "native$it"))) }
        val record = MirrorRecord(key, "Playlist", "r1", tracks, matches, ready = true)
        val context = MusicPlaybackContext("source", "native", "youtube")
        val queue = tracks.map { it.toMusicTrack("spotify") }
        val prepared = prepareMirrorPlayback(record, queue[1], queue, "ytm", context)
        assertThat(prepared.track.videoId).isEqualTo("source2")
        assertThat(prepared.queue.map { it.videoId }).containsExactly("source0", "source2", "source3").inOrder()
        assertThat(prepared.track.thumbnailUrl).isEqualTo("https://source.test/2")
        assertThat(prepared.track.title).isEqualTo("Source 2")
        assertThat(MusicVideoItems.descriptor(prepared.track).ids["ytm"]).isEqualTo("native2")
        val restored =
            PluginJson.decodeFromString(
                io.github.aedev.flow.data.music.model.MusicTrack
                    .serializer(),
                PluginJson.encodeToString(
                    io.github.aedev.flow.data.music.model.MusicTrack
                        .serializer(),
                    prepared.track,
                ),
            )
        assertThat(restored.playbackContext).isEqualTo(context)
    }
}
