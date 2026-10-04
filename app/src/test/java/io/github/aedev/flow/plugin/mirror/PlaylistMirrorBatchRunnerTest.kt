package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.playback.AudioBatchIndexingResult
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportMode
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportPhase
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportProgress
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.Test

class PlaylistMirrorBatchRunnerTest {
    @Test
    fun `capable target receives bounded matching batches with empty ensure in first root`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(34, batchMatching = true)
            val result = f.runner.prepare(f.key, "Playlist")
            assertThat(f.batchCalls.map { it.size }).containsExactly(16, 16, 2).inOrder()
            val ensure = checkNotNull(f.ensureRequests.first())
            assertThat(ensure.mode).isEqualTo(PrivatePlaylistImportMode.ENSURE)
            assertThat(ensure.tracks).isEmpty()
            assertThat(ensure.expectedAccountKey).isEqualTo("b")
            assertThat(f.ensureRequests.drop(1)).containsExactly(null, null)
            assertThat(result.matches).hasSize(34)
            assertThat(f.checkpoints.map { it.nextIndex }.distinct()).containsExactlyElementsIn(0..34).inOrder()
            coVerify(exactly = 0) { f.matcher.matchForIndexing(any(), any(), any()) }
            coVerify(exactly = 1) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `batch checkpoints retain repeated source occurrences and misses`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3, batchMatching = true)
            f.tracks = listOf(f.tracks[0], f.tracks[1], f.tracks[0])
            f.match = { if (it == f.tracks[1]) null else it }
            val states = mutableListOf<PlaylistMirrorState>()
            val result = f.runner.prepare(f.key, "Playlist", { states += it })
            assertThat(result.matches.map { it.sourcePosition }).containsExactly(0, 2).inOrder()
            assertThat(result.missed).containsExactly(f.tracks[1])
            assertThat(states.any { it.percentage == 85 && it.matched == 2 && it.missing == 1 }).isTrue()
            assertThat(states.dropLast(1).all { it.percentage < 100 }).isTrue()
            coVerify(exactly = 1) {
                f.host.call(
                    "target",
                    PluginOperations.importPrivatePlaylist,
                    match {
                        it.mode == PrivatePlaylistImportMode.REPLACE && it.tracks.size == 2 && it.tracks[0] == it.tracks[1]
                    },
                )
            }
        }

    @Test
    fun `ensure continuation waits until matching rounds finish`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(17, batchMatching = true)
            f.batch = { tracks, request ->
                AudioBatchIndexingResult(
                    tracks.map(f.match),
                    playlist =
                        request?.let {
                            PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"), "waiting", 1000)
                        },
                )
            }
            coEvery { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                assertThat(f.batchCalls).hasSize(2)
                if (request.mode == PrivatePlaylistImportMode.ENSURE) assertThat(request.cursor).isEqualTo("waiting")
                PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"))
            }
            assertThat(f.runner.prepare(f.key, "Playlist").ready).isTrue()
            assertThat(testScheduler.currentTime).isEqualTo(1000L)
        }

    @Test
    fun `successful positions checkpoint before slot error and survive replacement destination`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(4, batchMatching = true)
            f.batch = { tracks, request ->
                AudioBatchIndexingResult(
                    tracks.map(f.match),
                    playlist = request?.let { PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy")) },
                    errors = listOf(null, null, PluginError(PluginErrorCode.NETWORK, "offline"), null),
                )
            }
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PluginCallException::class.java)
            assertThat(f.stored?.destination?.providerId).isEqualTo("copy")
            assertThat(f.stored?.nextIndex).isEqualTo(2)
            assertThat(f.stored?.missed).isEmpty()
            f.batch = { tracks, request ->
                AudioBatchIndexingResult(
                    tracks.map(f.match),
                    playlist =
                        request?.let {
                            assertThat(it.target?.providerId).isEqualTo("copy")
                            PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "replacement"))
                        },
                )
            }
            coEvery { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val request = thirdArg<PrivatePlaylistImportRequest>()
                assertThat(request.target?.providerId).isEqualTo("replacement")
                PrivatePlaylistImportResult(request.target)
            }
            val result = f.runner.prepare(f.key, "Playlist")
            assertThat(result.destination?.providerId).isEqualTo("replacement")
            assertThat(f.batchCalls.last()).containsExactlyElementsIn(f.tracks.drop(2)).inOrder()
            assertThat(result.matches.map { it.sourcePosition }).containsExactly(0, 1, 2, 3).inOrder()
        }

    @Test
    fun `creation error preserves returned destination before throwing`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(1, batchMatching = true)
            f.batch = { tracks, _ ->
                AudioBatchIndexingResult(
                    tracks.map(f.match),
                    playlist = PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy")),
                    playlistError = PluginError(PluginErrorCode.NETWORK, "creation failed"),
                )
            }
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PluginCallException::class.java)
            assertThat(f.stored?.destination?.providerId).isEqualTo("copy")
            assertThat(f.stored?.ready).isFalse()
            coVerify(exactly = 0) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `cancellation during combined batch drains work without advancing checkpoint`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(2, batchMatching = true)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            f.batch = { tracks, request ->
                entered.complete(Unit)
                release.await()
                AudioBatchIndexingResult(
                    tracks.map(f.match),
                    playlist =
                        request?.let {
                            PrivatePlaylistImportResult(EntityRef(EntityKind.PLAYLIST, "copy"))
                        },
                )
            }
            val job = async { f.runner.prepare(f.key, "Playlist") }
            select {
                entered.onAwait { }
                job.onAwait { error("Matching bypassed combined batch") }
            }
            job.cancel()
            release.complete(Unit)
            job.join()
            assertThat(f.stored?.nextIndex).isEqualTo(0)
            assertThat(f.stored?.ready).isFalse()
            coVerify(exactly = 0) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `source revision change during combined batch prevents replacement`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(2, batchMatching = true)
            f.match = {
                f.revision = "changed"
                it
            }
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull() as MirrorPreparationException
            assertThat(failure.reason).isEqualTo(MirrorFailure.SOURCE_CHANGED)
            coVerify(exactly = 0) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `account change during batch cannot checkpoint returned creation state`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(2, batchMatching = true)
            f.match = {
                f.accounts.value = f.accounts.value + ("target" to ProviderAccount.SignedIn("other"))
                it
            }
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull() as MirrorPreparationException
            assertThat(failure.reason).isEqualTo(MirrorFailure.ACCOUNT_CHANGED)
            assertThat(f.stored?.destination).isNull()
            assertThat(f.stored?.nextIndex).isEqualTo(0)
            coVerify(exactly = 0) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `package replacement during batch cannot checkpoint matched positions`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(2, batchMatching = true)
            f.match = {
                val state = f.registryState.value
                f.registryState.value =
                    state.copy(
                        plugins =
                            state.plugins.map {
                                if (it.id == "target") it.copy(installedAtMs = it.installedAtMs + 1) else it
                            },
                    )
                it
            }
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull() as MirrorPreparationException
            assertThat(failure.reason).isEqualTo(MirrorFailure.PLUGIN_CHANGED)
            assertThat(f.stored?.nextIndex).isEqualTo(0)
            coVerify(exactly = 0) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `provider writing and verification counts remain below ready`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(4, batchMatching = true)
            val states = mutableListOf<PlaylistMirrorState>()
            coEvery { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) } answers {
                val cursor = thirdArg<PrivatePlaylistImportRequest>().cursor
                when (cursor) {
                    null -> {
                        PrivatePlaylistImportResult(
                            EntityRef(EntityKind.PLAYLIST, "copy"),
                            "verify",
                            progress = PrivatePlaylistImportProgress(PrivatePlaylistImportPhase.WRITING, 4, 4),
                        )
                    }

                    "verify" -> {
                        PrivatePlaylistImportResult(
                            EntityRef(EntityKind.PLAYLIST, "copy"),
                            "confirm",
                            progress = PrivatePlaylistImportProgress(PrivatePlaylistImportPhase.VERIFYING, 2, 4),
                        )
                    }

                    else -> {
                        PrivatePlaylistImportResult(
                            EntityRef(EntityKind.PLAYLIST, "copy"),
                            progress = PrivatePlaylistImportProgress(PrivatePlaylistImportPhase.VERIFYING, 4, 4),
                        )
                    }
                }
            }
            f.runner.prepare(f.key, "Playlist", { states += it })
            assertThat(states.map { it.percentage }).containsAtLeast(85, 95, 97, 99, 100).inOrder()
            assertThat(states.dropLast(1).all { !it.ready && it.percentage < 100 }).isTrue()
        }

    @Test
    fun `empty source ensures and verifies empty replacement without matching`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(0, batchMatching = true)
            val states = mutableListOf<PlaylistMirrorState>()
            assertThat(f.runner.prepare(f.key, "Empty", { states += it }).ready).isTrue()
            assertThat(f.batchCalls).isEmpty()
            assertThat(states.last().percentage).isEqualTo(100)
            assertThat(states.dropLast(1).all { it.percentage < 100 }).isTrue()
            coVerify(exactly = 1) {
                f.host.call(
                    "target",
                    PluginOperations.importPrivatePlaylist,
                    match {
                        it.mode == PrivatePlaylistImportMode.REPLACE && it.tracks.isEmpty()
                    },
                )
            }
        }
}
