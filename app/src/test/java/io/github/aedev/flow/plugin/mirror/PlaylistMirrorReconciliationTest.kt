package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistMirrorReconciliationTest {
    @Test
    fun `disabling one pair preserves another pair background and foreground preparation`() =
        runTest {
            val f = Fixture()
            val startedA = CompletableDeferred<Unit>()
            val startedB = CompletableDeferred<Unit>()
            val finishA = CompletableDeferred<MirrorRecord>()
            val finishB = CompletableDeferred<MirrorRecord>()
            coEvery { f.runner.prepare(f.a, any(), any(), any(), any()) } coAnswers {
                startedA.complete(Unit)
                finishA.await()
            }
            coEvery { f.runner.prepare(f.b, any(), any(), any(), any()) } coAnswers {
                startedB.complete(Unit)
                finishB.await()
            }
            val obsolete = async { runCatching { f.coordinator.prepare(f.a, "A") } }
            val background = async { runCatching { f.coordinator.prepare(f.b, "B", background = true) } }
            startedA.await()
            startedB.await()
            val foreground = async { runCatching { f.coordinator.prepare(f.b, "B") } }
            runCurrent()

            f.disableA()
            f.reconcile()
            finishA.complete(f.record(f.a, "A"))
            finishB.complete(f.record(f.b, "B"))

            assertThat(obsolete.await().isFailure).isTrue()
            assertThat(background.await().isSuccess).isTrue()
            assertThat(foreground.await().isSuccess).isTrue()
            coVerify(exactly = 1) { f.runner.prepare(f.b, any(), any(), any(), any()) }
        }

    @Test
    fun `another pair changing preserves the surviving one-use playback handoff`() =
        runTest {
            val f = Fixture()
            val record = f.record(f.b, "B")
            coEvery { f.runner.prepare(f.b, any(), any(), any(), any()) } returns record
            f.coordinator.prepare(f.b, "B")

            f.disableA()
            f.reconcile()

            assertThat(f.coordinator.prepareForPlayback(f.b, "B")).isSameInstanceAs(record)
            coVerify(exactly = 1) { f.runner.prepare(f.b, any(), any(), any(), any()) }
            f.coordinator.prepareForPlayback(f.b, "B")
            coVerify(exactly = 2) { f.runner.prepare(f.b, any(), any(), any(), any()) }
        }

    @Test
    fun `account changes and plugin replacements invalidate only their own pair`() =
        runTest {
            for (changeAccount in listOf(true, false)) {
                val f = Fixture()
                val startedA = CompletableDeferred<Unit>()
                val startedB = CompletableDeferred<Unit>()
                val finishA = CompletableDeferred<MirrorRecord>()
                val finishB = CompletableDeferred<MirrorRecord>()
                coEvery { f.runner.prepare(f.a, any(), any(), any(), any()) } coAnswers {
                    startedA.complete(Unit)
                    finishA.await()
                }
                coEvery { f.runner.prepare(f.b, any(), any(), any(), any()) } coAnswers {
                    startedB.complete(Unit)
                    finishB.await()
                }
                val obsolete = async { runCatching { f.coordinator.prepare(f.a, "A") } }
                val survivor = async { runCatching { f.coordinator.prepare(f.b, "B") } }
                startedA.await()
                startedB.await()

                if (changeAccount) {
                    f.accountStates.value = f.accountStates.value + ("source-a" to ProviderAccount.SignedIn("replacement"))
                } else {
                    f.plugins.value =
                        f.plugins.value.copy(
                            plugins =
                                f.plugins.value.plugins.map {
                                    if (it.id == "source-a") it.copy(installedAtMs = 1) else it
                                },
                        )
                }
                f.reconcile()
                finishA.complete(f.record(f.a, "A"))
                finishB.complete(f.record(f.b, "B"))

                assertThat(obsolete.await().isFailure).isTrue()
                assertThat(survivor.await().isSuccess).isTrue()
                f.coordinator.prepareForPlayback(f.b, "B")
                coVerify(exactly = 1) { f.runner.prepare(f.b, any(), any(), any(), any()) }
            }
        }

    @Test
    fun `canceled work finishing after re-enabling cannot resurrect its handoff`() =
        runTest {
            val f = Fixture()
            val started = CompletableDeferred<Job>()
            val finish = CompletableDeferred<MirrorRecord>()
            val old = f.record(f.a, "A")
            val fresh = old.copy(revision = "fresh")
            var calls = 0
            coEvery { f.runner.prepare(f.a, any(), any(), any(), any()) } coAnswers {
                if (++calls == 1) {
                    started.complete(currentCoroutineContext()[Job]!!)
                    withContext(NonCancellable) { finish.await() }
                } else {
                    fresh
                }
            }
            val page = async { runCatching { f.coordinator.prepare(f.a, "A") } }
            val oldJob = started.await()
            f.disableA()
            f.reconcile()
            f.pairs.value = f.pairs.value + PlaylistMirrorStore.pairId("source-a", "target")
            f.reconcile()
            finish.complete(old)
            oldJob.join()
            assertThat(page.await().isFailure).isTrue()

            assertThat(f.coordinator.prepareForPlayback(f.a, "A")).isSameInstanceAs(fresh)
            coVerify(exactly = 2) { f.runner.prepare(f.a, any(), any(), any(), any()) }
        }

    private class Fixture {
        val a = MirrorKey("source-a", "a", "target", "t", EntityRef(EntityKind.PLAYLIST, "playlist-a"))
        val b = MirrorKey("source-b", "b", "target", "t", EntityRef(EntityKind.PLAYLIST, "playlist-b"))
        val runner = mockk<PlaylistMirrorRunner>()
        val plugins = MutableStateFlow(PluginRegistryState(listOf(plugin("source-a", true), plugin("source-b", true), plugin("target"))))
        val accountStates =
            MutableStateFlow<Map<String, ProviderAccount>>(
                mapOf(
                    "source-a" to ProviderAccount.SignedIn("a"),
                    "source-b" to ProviderAccount.SignedIn("b"),
                    "target" to ProviderAccount.SignedIn("t"),
                ),
            )
        val pairs =
            MutableStateFlow(setOf(PlaylistMirrorStore.pairId("source-a", "target"), PlaylistMirrorStore.pairId("source-b", "target")))
        val coordinator =
            PlaylistMirrorCoordinator(
                runner,
                mockk {
                    every { enabledPairs } returns pairs
                    coEvery { this@mockk.get(any()) } returns null
                },
                mockk { every { state } returns plugins },
                mockk { every { accounts } returns accountStates },
                MirrorExecutionGate(),
            )

        fun disableA() {
            pairs.value = setOf(PlaylistMirrorStore.pairId("source-b", "target"))
        }

        fun reconcile() {
            coordinator.cancelObsolete(
                listOf(a, b).mapNotNull { key ->
                    if (PlaylistMirrorStore.pairId(key.sourcePlugin, key.targetPlugin) in pairs.value) {
                        coordinator.key(key.sourcePlugin, key.targetPlugin, EntityRef(EntityKind.PLAYLIST, "owned"))
                    } else {
                        null
                    }
                },
            )
        }

        fun record(
            key: MirrorKey,
            title: String,
        ) = MirrorRecord(
            key,
            title,
            "revision",
            emptyList(),
            destination = EntityRef(EntityKind.PLAYLIST, "copy-${key.source.providerId}"),
            ready = true,
        )
    }
}

private fun plugin(
    id: String,
    source: Boolean = false,
) = InstalledPlugin(
    manifest =
        PluginManifest(
            format = 1,
            api = ApiRange(3, 3),
            id = id,
            name = id,
            version = "1",
            versionCode = 1,
            roles =
                Roles(
                    metadata =
                        MetadataRole(
                            emptySet(),
                            setOf(EntityKind.PLAYLIST),
                            id,
                            personalCollections = source,
                            privatePlaylistImport = !source,
                        ),
                    audio = if (source) null else AudioRole(setOf(id)),
                ),
        ),
    signerFingerprint = "",
    sourceUrl = "",
    installedAtMs = 0,
    grantedNetwork = emptyList(),
    grantedBrowser = emptyList(),
)
