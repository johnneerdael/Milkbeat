package io.github.aedev.flow.plugin.mirror

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistMirrorCoordinatorTest {
    @Test
    fun `expired destination sign-in updates the visible account state`() =
        runTest {
            val runner = mockk<PlaylistMirrorRunner>()
            val accounts = mockk<PluginAccounts>(relaxed = true)
            every { accounts.accounts } returns MutableStateFlow(emptyMap())
            coEvery { runner.prepare(any(), any(), any(), any(), any()) } throws
                PluginCallException("target", PluginError(PluginErrorCode.SIGN_IN_EXPIRED, "Expired"))
            val coordinator =
                PlaylistMirrorCoordinator(
                    runner,
                    mockk(),
                    mockk {
                        every { state } returns MutableStateFlow(PluginRegistryState())
                    },
                    accounts,
                    MirrorExecutionGate(),
                )
            val key = MirrorKey("source", "a", "target", "b", EntityRef(EntityKind.PLAYLIST, "playlist"))
            val failure = runCatching { coordinator.prepare(key, "Playlist") }.exceptionOrNull()
            assertThat(failure).isInstanceOf(PluginCallException::class.java)
            verify(exactly = 1) { accounts.expired("target") }
        }

    @Test
    fun `stopping background consumer retains preparation shared by foreground`() =
        runTest {
            val runner = mockk<PlaylistMirrorRunner>()
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<MirrorRecord>()
            coEvery { runner.prepare(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
            }
            val coordinator =
                PlaylistMirrorCoordinator(
                    runner,
                    mockk<PlaylistMirrorStore>(),
                    mockk<PluginRegistry> { every { state } returns MutableStateFlow(PluginRegistryState()) },
                    mockk<PluginAccounts> { every { accounts } returns MutableStateFlow(emptyMap()) },
                    MirrorExecutionGate(),
                )
            val key = MirrorKey("source", "a", "target", "b", EntityRef(EntityKind.PLAYLIST, "playlist"))
            val background = async { coordinator.prepare(key, "Playlist", true) }
            started.await()
            val foreground = async { coordinator.prepare(key, "Playlist") }
            runCurrent()
            background.cancelAndJoin()
            finish.complete(MirrorRecord(key, "Playlist", "r1", emptyList(), ready = true))
            assertThat(foreground.await().ready).isTrue()
            coVerify(exactly = 1) { runner.prepare(any(), any(), any(), any(), any()) }
        }

    private class PlaybackFixture(
        gate: MirrorExecutionGate = MirrorExecutionGate(),
    ) {
        val key = MirrorKey("source", "a", "target", "b", EntityRef(EntityKind.PLAYLIST, "playlist"))
        val runner = mockk<PlaylistMirrorRunner>()
        val plugins = MutableStateFlow(PluginRegistryState(emptyList()))
        val accountStates =
            MutableStateFlow<Map<String, ProviderAccount>>(
                mapOf(
                    "source" to ProviderAccount.SignedIn("a"),
                    "target" to ProviderAccount.SignedIn("b"),
                ),
            )
        val pairs = MutableStateFlow(setOf(PlaylistMirrorStore.pairId("source", "target")))
        val record =
            MirrorRecord(key, "Playlist", "r1", emptyList(), destination = EntityRef(EntityKind.PLAYLIST, "private-copy"), ready = true)
        val coordinator =
            PlaylistMirrorCoordinator(
                runner,
                mockk { every { enabledPairs } returns pairs },
                mockk {
                    every { state } returns
                        plugins
                },
                mockk { every { accounts } returns accountStates },
                gate,
            )

        init {
            coEvery { runner.prepare(any(), any(), any(), any(), any()) } returns record
        }
    }

    @Test
    fun `Play reuses a successful page preparation exactly once`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            f.coordinator.prepare(f.key, "Playlist")
            var notices = 0
            assertThat(f.coordinator.prepareForPlayback(f.key, "Playlist") { notices++ }).isSameInstanceAs(f.record)
            assertThat(notices).isEqualTo(0)
            coVerify(exactly = 1) { f.runner.prepare(any(), any(), any(), any(), any()) }
            f.coordinator.prepareForPlayback(f.key, "Playlist")
            coVerify(exactly = 2) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `reopening a page refreshes and invalidation prevents reuse of its completed preparation`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            f.coordinator.prepare(f.key, "Playlist")
            f.coordinator.prepare(f.key, "Playlist")
            coVerify(exactly = 2) { f.runner.prepare(any(), any(), any(), any(), any()) }
            f.coordinator.cancelObsolete()
            f.coordinator.prepareForPlayback(f.key, "Playlist")
            coVerify(exactly = 3) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `plugin replacement invalidates the verified handoff even when its account key is unchanged`() =
        runTest {
            val f = PlaybackFixture()
            val source = plugin("source", true)
            val target = plugin("target")
            f.plugins.value = PluginRegistryState(listOf(source, target))
            f.coordinator.prepare(f.key, "Playlist")
            f.plugins.value = PluginRegistryState(listOf(source, target.copy(installedAtMs = 1)))
            f.coordinator.prepareForPlayback(f.key, "Playlist")
            coVerify(exactly = 2) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `Play joins preparation still running on the page`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<MirrorRecord>()
            coEvery { f.runner.prepare(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
            }
            val page = async { f.coordinator.prepare(f.key, "Playlist") }
            started.await()
            var notices = 0
            val play = async { f.coordinator.prepareForPlayback(f.key, "Playlist") { notices++ } }
            runCurrent()
            assertThat(notices).isEqualTo(1)
            assertThat(play.isCompleted).isFalse()
            finish.complete(f.record)
            assertThat(play.await()).isSameInstanceAs(page.await())
            assertThat(notices).isEqualTo(1)
            coVerify(exactly = 1) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `Play reports waiting when starting a new preparation`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<MirrorRecord>()
            coEvery { f.runner.prepare(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
            }
            var notices = 0
            val play = async { f.coordinator.prepareForPlayback(f.key, "Playlist") { notices++ } }
            started.await()
            assertThat(notices).isEqualTo(1)
            assertThat(play.isCompleted).isFalse()
            finish.complete(f.record)
            assertThat(play.await()).isSameInstanceAs(f.record)
            assertThat(notices).isEqualTo(1)
            coVerify(exactly = 1) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `Play waiting consumer can cancel without cancelling the page preparation`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<MirrorRecord>()
            coEvery { f.runner.prepare(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
            }
            val page = async { f.coordinator.prepare(f.key, "Playlist") }
            started.await()
            var notices = 0
            val play = async { f.coordinator.prepareForPlayback(f.key, "Playlist") { notices++ } }
            runCurrent()
            play.cancelAndJoin()
            assertThat(notices).isEqualTo(1)
            finish.complete(f.record)
            assertThat(page.await()).isSameInstanceAs(f.record)
            coVerify(exactly = 1) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `failed page retry clears the older successful handoff`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            f.coordinator.prepare(f.key, "Playlist")
            coEvery { f.runner.prepare(any(), any(), any(), any(), any()) } throws IllegalStateException("offline")
            assertThat(runCatching { f.coordinator.prepare(f.key, "Playlist") }.isFailure).isTrue()
            assertThat(runCatching { f.coordinator.prepareForPlayback(f.key, "Playlist") }.isFailure).isTrue()
            coVerify(exactly = 3) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `disabled mirroring and expired accounts cannot start with a saved handoff`() =
        runTest {
            for (disablePair in listOf(true, false)) {
                val f = PlaybackFixture()
                f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
                f.coordinator.prepare(f.key, "Playlist")
                if (disablePair) {
                    f.pairs.value = emptySet()
                } else {
                    f.accountStates.value =
                        f.accountStates.value + ("target" to ProviderAccount.Expired)
                }
                var notices = 0
                assertThat(runCatching { f.coordinator.prepareForPlayback(f.key, "Playlist") { notices++ } }.isFailure).isTrue()
                assertThat(notices).isEqualTo(0)
                coVerify(exactly = 1) { f.runner.prepare(any(), any(), any(), any(), any()) }
            }
        }

    @Test
    fun `background refresh of the same copy invalidates the page handoff`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            f.coordinator.prepare(f.key, "Playlist")
            f.coordinator.prepare(f.key, "Playlist", background = true)
            f.coordinator.prepareForPlayback(f.key, "Playlist")
            coVerify(exactly = 3) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `page joining background preparation hands its verified result to Play`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<MirrorRecord>()
            coEvery { f.runner.prepare(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
            }
            val background = async { f.coordinator.prepare(f.key, "Playlist", background = true) }
            started.await()
            val page = async { f.coordinator.prepare(f.key, "Playlist") }
            runCurrent()
            finish.complete(f.record)
            page.await()
            background.await()
            assertThat(f.coordinator.prepareForPlayback(f.key, "Playlist")).isSameInstanceAs(f.record)
            coVerify(exactly = 1) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `completion while Play enters the foreground gate does not start another preparation`() =
        runTest {
            val gate = mockk<MirrorExecutionGate>()
            val f = PlaybackFixture(gate)
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            val playEntered = CompletableDeferred<Unit>()
            val releasePlay = CompletableDeferred<Unit>()
            var entries = 0
            coEvery { gate.foreground<MirrorRecord>(any(), any()) } coAnswers {
                if (++entries == 2) {
                    playEntered.complete(Unit)
                    releasePlay.await()
                }
                secondArg<suspend () -> MirrorRecord>().invoke()
            }
            val started = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<MirrorRecord>()
            coEvery { f.runner.prepare(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(Unit)
                finish.await()
            }
            val page = async { f.coordinator.prepare(f.key, "Playlist") }
            started.await()
            var notices = 0
            val play = async { f.coordinator.prepareForPlayback(f.key, "Playlist") { notices++ } }
            playEntered.await()
            finish.complete(f.record)
            page.await()
            releasePlay.complete(Unit)
            assertThat(play.await()).isSameInstanceAs(f.record)
            assertThat(notices).isEqualTo(0)
            coVerify(exactly = 1) { f.runner.prepare(any(), any(), any(), any(), any()) }
        }

    @Test
    fun `Play can claim completed work before the page continuation resumes`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            val pageScheduler = TestCoroutineScheduler()
            val pageScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(pageScheduler))
            val started = CompletableDeferred<Job>()
            val finish = CompletableDeferred<MirrorRecord>()
            coEvery { f.runner.prepare(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(currentCoroutineContext()[Job]!!)
                finish.await()
            }
            try {
                val page = pageScope.async { f.coordinator.prepare(f.key, "Playlist") }
                pageScheduler.runCurrent()
                val runnerJob = started.await()
                finish.complete(f.record)
                runnerJob.join()
                assertThat(page.isCompleted).isFalse()
                assertThat(f.coordinator.prepareForPlayback(f.key, "Playlist")).isSameInstanceAs(f.record)
                coVerify(exactly = 1) { f.runner.prepare(any(), any(), any(), any(), any()) }
                pageScheduler.runCurrent()
                page.await()
            } finally {
                pageScope.cancel()
            }
        }

    @Test
    fun `a late second page waiter cannot restore a handoff consumed by Play`() =
        runTest {
            val f = PlaybackFixture()
            f.plugins.value = PluginRegistryState(listOf(plugin("source", true), plugin("target")))
            val firstScheduler = TestCoroutineScheduler()
            val secondScheduler = TestCoroutineScheduler()
            val firstScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(firstScheduler))
            val secondScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(secondScheduler))
            val started = CompletableDeferred<Job>()
            val finish = CompletableDeferred<MirrorRecord>()
            coEvery { f.runner.prepare(any(), any(), any(), any(), any()) } coAnswers {
                started.complete(currentCoroutineContext()[Job]!!)
                finish.await()
            }
            try {
                val first = firstScope.async { f.coordinator.prepare(f.key, "Playlist") }
                firstScheduler.runCurrent()
                val runnerJob = started.await()
                val second = secondScope.async { f.coordinator.prepare(f.key, "Playlist") }
                secondScheduler.runCurrent()
                finish.complete(f.record)
                runnerJob.join()
                firstScheduler.runCurrent()
                first.await()
                f.coordinator.prepareForPlayback(f.key, "Playlist")
                coVerify(exactly = 1) { f.runner.prepare(any(), any(), any(), any(), any()) }
                secondScheduler.runCurrent()
                second.await()
                f.coordinator.prepareForPlayback(f.key, "Playlist")
                coVerify(exactly = 2) { f.runner.prepare(any(), any(), any(), any(), any()) }
            } finally {
                firstScope.cancel()
                secondScope.cancel()
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

    @Test
    fun `mirror selection reacts to pairs accounts and compatible installed targets without preparing`() =
        runTest {
            val source = plugin("source", source = true)
            val target = plugin("target")
            val installed = MutableStateFlow(PluginRegistryState(listOf(source, target)))
            val accountStates =
                MutableStateFlow<Map<String, ProviderAccount>>(
                    mapOf("source" to ProviderAccount.SignedIn("a"), "target" to ProviderAccount.SignedIn("b")),
                )
            val pairs = MutableStateFlow(setOf(PlaylistMirrorStore.pairId("source", "target")))
            val runner = mockk<PlaylistMirrorRunner>()
            val coordinator =
                PlaylistMirrorCoordinator(
                    runner,
                    mockk { every { enabledPairs } returns pairs },
                    mockk { every { state } returns installed },
                    mockk { every { accounts } returns accountStates },
                    MirrorExecutionGate(),
                )
            val playlist = EntityRef(EntityKind.PLAYLIST, "playlist")
            val key = MirrorKey("source", "a", "target", "b", playlist)
            val emitted = mutableListOf<MirrorKey?>()
            backgroundScope.launch { coordinator.observeSelectedKey("source", playlist).collect { emitted += it } }
            runCurrent()
            assertThat(emitted).containsExactly(key)
            assertThat(coordinator.selectedKey("source", playlist)).isEqualTo(key)

            pairs.value = emptySet()
            runCurrent()
            assertThat(emitted.last()).isNull()
            assertThat(coordinator.selectedKey("source", playlist)).isNull()
            pairs.value = setOf(PlaylistMirrorStore.pairId("source", "target"))
            runCurrent()
            assertThat(emitted.last()).isEqualTo(key)

            accountStates.value = accountStates.value + ("source" to ProviderAccount.Anonymous)
            runCurrent()
            assertThat(emitted.last()).isNull()
            accountStates.value = accountStates.value + ("source" to ProviderAccount.SignedIn("new-source"))
            runCurrent()
            val changedSource = key.copy(sourceAccount = "new-source")
            assertThat(emitted.last()).isEqualTo(changedSource)
            accountStates.value = accountStates.value + ("target" to ProviderAccount.Expired)
            runCurrent()
            assertThat(emitted.last()).isNull()
            accountStates.value = accountStates.value + ("target" to ProviderAccount.SignedIn("new-target"))
            runCurrent()
            val changedAccounts = changedSource.copy(targetAccount = "new-target")
            assertThat(emitted.last()).isEqualTo(changedAccounts)

            installed.value = PluginRegistryState(listOf(source))
            runCurrent()
            assertThat(emitted.last()).isNull()
            installed.value = PluginRegistryState(listOf(source, target.copy(enabled = false)))
            runCurrent()
            assertThat(emitted.last()).isNull()
            assertThat(coordinator.selectedKey("source", playlist)).isNull()
            installed.value = PluginRegistryState(listOf(source, target))
            runCurrent()
            assertThat(emitted.last()).isEqualTo(changedAccounts)
            accountStates.value = accountStates.value + ("unrelated" to ProviderAccount.Anonymous)
            runCurrent()

            assertThat(emitted).containsExactly(key, null, key, null, changedSource, null, changedAccounts, null, changedAccounts).inOrder()
            coVerify(exactly = 0) { runner.prepare(any(), any(), any(), any(), any()) }
        }
}
