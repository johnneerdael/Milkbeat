package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportMode
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackList
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.Test

class PlaylistMirrorRunnerTest {
    @Test
    fun `fresh runner opens a ready persisted mirror without source fetch matching or import`() =
        runTest {
            val initial = PlaylistMirrorRunnerFixture(3)
            val prepared = initial.runner.prepare(initial.key, "Playlist")
            val restarted = PlaylistMirrorRunnerFixture(3)
            restarted.stored =
                PluginJson.decodeFromString(MirrorRecord.serializer(), PluginJson.encodeToString(MirrorRecord.serializer(), prepared))
            val events = mutableListOf<String>()
            coEvery { restarted.host.call("source", PluginOperations.tracks, any()) } answers {
                events += "source"
                TrackList(restarted.tracks, revision = restarted.revision)
            }
            coEvery { restarted.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                assertThat(request.target).isEqualTo(prepared.destination)
                events += request.mode.name
                PrivatePlaylistImportResult(prepared.destination)
            }
            val result = restarted.runner.prepare(restarted.key, "Playlist")
            assertThat(events).isEmpty()
            assertThat(result.destination).isEqualTo(prepared.destination)
            assertThat(result.matches).isEqualTo(prepared.matches)
            assertThat(result.ready).isTrue()
            assertThat(restarted.calls).isEmpty()
        }

    @Test
    fun `refresh after restart changes order additions and removals on the saved destination`() =
        runTest {
            val initial = PlaylistMirrorRunnerFixture(3)
            val prepared = initial.runner.prepare(initial.key, "Playlist")
            val restarted = PlaylistMirrorRunnerFixture(4)
            restarted.stored =
                PluginJson.decodeFromString(MirrorRecord.serializer(), PluginJson.encodeToString(MirrorRecord.serializer(), prepared))
            restarted.tracks = listOf(restarted.tracks[2], restarted.tracks[0], restarted.tracks[3], restarted.tracks[0])
            restarted.revision = "r2"
            val imports = mutableListOf<PrivatePlaylistImportRequest>()
            coEvery { restarted.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                imports += request
                assertThat(request.target).isEqualTo(prepared.destination)
                PrivatePlaylistImportResult(prepared.destination)
            }
            val result = restarted.runner.prepare(restarted.key, "Renamed", background = true)
            assertThat(result.destination).isEqualTo(prepared.destination)
            assertThat(result.title).isEqualTo("Renamed")
            assertThat(result.ready).isTrue()
            assertThat(
                imports.last().tracks.map {
                    it.providerId
                },
            ).containsExactly("nativetrack2", "nativetrack0", "nativetrack3", "nativetrack0").inOrder()
        }

    @Test
    fun `new playlist availability waits suspend instead of polling immediately`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(1)
            var waiting = true
            coEvery { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                if (thirdArg<PrivatePlaylistImportRequest>().mode == PrivatePlaylistImportMode.ENSURE && waiting) {
                    waiting = false
                    PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"), "waiting", 1000)
                } else {
                    PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"))
                }
            }
            assertThat(f.runner.prepare(f.key, "Playlist").ready).isTrue()
            assertThat(testScheduler.currentTime).isEqualTo(1000L)
        }

    @Test
    fun `replacement of a partial destination retains matches for final replacement`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            f.match = { if (it == f.tracks[1]) error("offline") else it }
            runCatching { f.runner.prepare(f.key, "Playlist") }
            assertThat(f.stored?.nextIndex).isEqualTo(1)
            val imports = mutableListOf<PrivatePlaylistImportRequest>()
            f.match = { it }
            coEvery { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                imports += request
                PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "replacement"))
            }
            assertThat(f.runner.prepare(f.key, "Playlist").ready).isTrue()
            assertThat(
                imports.map { it.mode },
            ).containsExactly(PrivatePlaylistImportMode.ENSURE, PrivatePlaylistImportMode.REPLACE).inOrder()
            assertThat(imports.last().tracks).hasSize(3)
        }

    @Test
    fun `playback without artwork reuses an already prepared source cover`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            val cover = Artwork("https://images.example.com/cover.jpg")
            val first = f.runner.prepare(f.key, "Playlist", artwork = cover)
            val second = f.runner.prepare(f.key, "Playlist")
            assertThat(second.revision).isEqualTo(first.revision)
            assertThat(second.artwork).isEqualTo(cover)
            assertThat(f.calls).hasSize(3)
            coVerify(exactly = 2) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `legacy destination is created before matching and resolved tracks are replaced together`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            val events = mutableListOf<String>()
            coEvery { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                events += "${request.mode}:${request.startIndex}"
                PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"))
            }
            f.match = {
                events += it.ref.providerId
                it
            }
            f.runner.prepare(f.key, "Playlist")
            assertThat(
                events,
            ).containsExactly("ENSURE:null", "track0", "track1", "track2", "REPLACE:null").inOrder()
        }

    @Test
    fun `whole source is matched sequentially preserving duplicates and resumed progress`() =
        runTest {
            val fixture = PlaylistMirrorRunnerFixture(122)
            fixture.tracks = fixture.tracks.dropLast(1) + fixture.tracks.first()
            val first = fixture.runner.prepare(fixture.key, "Playlist")
            assertThat(first.matches).hasSize(122)
            assertThat(first.matches.last().sourcePosition).isEqualTo(121)
            assertThat(first.ready).isTrue()
            assertThat(fixture.calls).hasSize(122)
            fixture.runner.prepare(fixture.key, "Playlist")
            assertThat(fixture.calls).hasSize(122)
            coVerify(exactly = 2) { fixture.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `temporary failure keeps checkpoint and resumes rather than recording a miss`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(4)
            var fail = true
            f.match = { track -> if (track == f.tracks[2] && fail) error("offline") else track }
            assertThat(runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull()?.message).isEqualTo("offline")
            assertThat(f.stored?.nextIndex).isEqualTo(2)
            assertThat(f.stored?.missed).isEmpty()
            fail = false
            val result = f.runner.prepare(f.key, "Playlist")
            assertThat(result.ready).isTrue()
            assertThat(f.calls.count { it == "track0" }).isEqualTo(1)
            assertThat(f.calls.count { it == "track2" }).isEqualTo(2)
        }

    @Test
    fun `confirmed misses preserve source occurrence mapping`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            f.match = { if (it == f.tracks[1]) null else it }
            val result = f.runner.prepare(f.key, "Playlist")
            assertThat(result.matches.map { it.sourcePosition }).containsExactly(0, 2).inOrder()
            assertThat(result.missed).containsExactly(f.tracks[1])
        }

    @Test
    fun `account changes cannot commit a stale match or write a copy`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            f.match = {
                f.accounts.value = f.accounts.value + ("target" to ProviderAccount.SignedIn("other"))
                it
            }
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull() as MirrorPreparationException
            assertThat(failure.reason).isEqualTo(MirrorFailure.ACCOUNT_CHANGED)
            assertThat(f.stored?.nextIndex).isEqualTo(0)
            coVerify(exactly = 0) {
                f.host.call(
                    "target",
                    PluginOperations.importPrivatePlaylist,
                    match {
                        it.mode ==
                            PrivatePlaylistImportMode.REPLACE
                    },
                )
            }
        }

    @Test
    fun `source edit during matching is detected before writing and retried from changed source`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            var changed = false
            f.match = { track ->
                if (!changed) {
                    changed = true
                    f.revision = "r2"
                }
                track
            }
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull() as MirrorPreparationException
            assertThat(failure.reason).isEqualTo(MirrorFailure.SOURCE_CHANGED)
            coVerify(exactly = 0) {
                f.host.call(
                    "target",
                    PluginOperations.importPrivatePlaylist,
                    match {
                        it.mode ==
                            PrivatePlaylistImportMode.REPLACE
                    },
                )
            }
            assertThat(f.runner.prepare(f.key, "Playlist").ready).isTrue()
        }
}
