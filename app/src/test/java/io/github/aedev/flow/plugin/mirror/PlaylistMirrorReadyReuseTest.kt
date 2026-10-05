package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import io.mockk.coVerify
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlaylistMirrorReadyReuseTest {
    @Test
    fun `background refresh still checks source changes in a ready mirror`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            val first = f.runner.prepare(f.key, "Playlist")
            f.tracks = f.tracks.reversed()
            f.revision = "r2"
            val refreshed = f.runner.prepare(f.key, "Playlist", background = true)
            assertThat(refreshed.destination).isEqualTo(first.destination)
            assertThat(refreshed.tracks).isEqualTo(f.tracks)
            assertThat(refreshed.matches.map { it.sourceTrack }).containsExactlyElementsIn(f.tracks).inOrder()
            assertThat(refreshed.verifiedAtMs).isGreaterThan(0)
        }

    @Test
    fun `six hour old mirrors check the source again on open`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            f.runner.prepare(f.key, "Playlist")
            f.stored = f.stored!!.copy(verifiedAtMs = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(6))
            f.tracks = f.tracks.reversed()
            f.revision = "r2"
            val refreshed = f.runner.prepare(f.key, "Playlist")
            assertThat(refreshed.tracks).isEqualTo(f.tracks)
            coVerify(exactly = 4) { f.host.call("source", PluginOperations.tracks, any()) }
        }

    @Test
    fun `ready reuse validates the signed in accounts before returning`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(1)
            f.runner.prepare(f.key, "Playlist")
            f.accounts.value = f.accounts.value + ("target" to ProviderAccount.SignedIn("other"))
            val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull() as MirrorPreparationException
            assertThat(failure.reason).isEqualTo(MirrorFailure.ACCOUNT_CHANGED)
        }

    @Test
    fun `legacy verified records receive a reuse deadline without network work`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(1)
            val first = f.runner.prepare(f.key, "Playlist")
            f.stored = first.copy(verifiedAtMs = 0)
            val states = mutableListOf<PlaylistMirrorState>()
            val ready = f.runner.prepare(f.key, "Playlist", { states += it })
            assertThat(ready.verifiedAtMs).isGreaterThan(0)
            assertThat(states.single().ready).isTrue()
            assertThat(states.single().isPreparing).isFalse()
            coVerify(exactly = 2) { f.host.call("source", PluginOperations.tracks, any()) }
            coVerify(exactly = 2) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `old policy misses are retried once and the updated ready mirror is then reused`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            f.match = { if (it == f.tracks[1]) null else it }
            val partial = f.runner.prepare(f.key, "Playlist")
            f.stored = partial.copy(matchingPolicyVersion = 0)
            f.match = { it }
            val updated = f.runner.prepare(f.key, "Playlist")
            assertThat(updated.matches).hasSize(3)
            assertThat(updated.missed).isEmpty()
            val calls = f.calls.size
            f.runner.prepare(f.key, "Playlist")
            assertThat(f.calls).hasSize(calls)
        }

    @Test
    fun `provider replacement requires verification of the saved ready copy`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(1)
            f.runner.prepare(f.key, "Playlist")
            f.registryState.value =
                f.registryState.value.copy(
                    plugins =
                        f.registryState.value.plugins
                            .map { if (it.id == "target") it.copy(installedAtMs = 1) else it },
                )
            f.runner.prepare(f.key, "Playlist")
            coVerify(exactly = 3) { f.host.call("source", PluginOperations.tracks, any()) }
            coVerify(exactly = 3) { f.host.call("target", PluginOperations.importPrivatePlaylist, any()) }
        }

    @Test
    fun `current policy misses retain checkpoints after no matches are found`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(3)
            f.match = { null }
            repeat(2) {
                val failure = runCatching { f.runner.prepare(f.key, "Playlist") }.exceptionOrNull() as MirrorPreparationException
                assertThat(failure.reason).isEqualTo(MirrorFailure.NO_MATCHES)
            }
            assertThat(f.calls).hasSize(3)
            assertThat(f.stored!!.nextIndex).isEqualTo(3)
        }

    @Test
    fun `renamed playlists refresh instead of reusing the stale title`() =
        runTest {
            val f = PlaylistMirrorRunnerFixture(1)
            f.runner.prepare(f.key, "Playlist")
            assertThat(f.runner.prepare(f.key, "New title").title).isEqualTo("New title")
        }
}
