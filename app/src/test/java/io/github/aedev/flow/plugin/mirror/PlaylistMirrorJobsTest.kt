package io.github.aedev.flow.plugin.mirror

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.MetadataRole
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.SignInMethod
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PlaylistMirrorJobsTest {
    @Test
    fun `startup refreshes enabled sign-in plugins without saved mirror pairs`() =
        runTest {
            val source = plugin("source", true)
            val disabled = plugin("disabled", true).also { every { it.enabled } returns false }
            val public = plugin("public")
            every { source.manifest.signIn } returns listOf(mockk<SignInMethod>())
            every { disabled.manifest.signIn } returns listOf(mockk<SignInMethod>())
            every { public.manifest.signIn } returns emptyList()
            val installed = MutableStateFlow(PluginRegistryState(listOf(source, disabled, public)))
            val registry = mockk<PluginRegistry> { every { state } returns installed }
            val enabled = MutableStateFlow(emptySet<String>())
            val store = mockk<PlaylistMirrorStore> { every { enabledPairs } returns enabled }
            val host = mockk<PluginHost>()
            coEvery { host.call("source", PluginOperations.account, Unit) } returns ProviderAccount.SignedIn("listener")
            val accounts = PluginAccounts(host)
            val coordinator = PlaylistMirrorCoordinator(mockk(), store, registry, accounts, MirrorExecutionGate())
            mockkObject(WorkManager.Companion)
            try {
                every { WorkManager.getInstance(any<Context>()) } returns mockk(relaxed = true)
                PlaylistMirrorJobs(mockk(), store, coordinator, accounts, registry).start(backgroundScope)
                runCurrent()
                assertEquals(mapOf("source" to ProviderAccount.SignedIn("listener")), accounts.accounts.value)
            } finally {
                unmockkObject(WorkManager.Companion)
            }
        }

    @Test
    fun `unrelated mirror and registry changes do not refresh surviving sign-in plugins`() =
        runTest {
            val source = plugin("source", true)
            every { source.manifest.signIn } returns listOf(mockk<SignInMethod>())
            val installed = MutableStateFlow(PluginRegistryState(listOf(source)))
            val registry = mockk<PluginRegistry> { every { state } returns installed }
            val enabled = MutableStateFlow(setOf("source|missing-target"))
            val store = mockk<PlaylistMirrorStore> { every { enabledPairs } returns enabled }
            val host = mockk<PluginHost>()
            var checks = 0
            coEvery { host.call("source", PluginOperations.account, Unit) } coAnswers { ProviderAccount.SignedIn("listener-${++checks}") }
            val accounts = PluginAccounts(host)
            val coordinator = PlaylistMirrorCoordinator(mockk(), store, registry, accounts, MirrorExecutionGate())
            mockkObject(WorkManager.Companion)
            try {
                every { WorkManager.getInstance(any<Context>()) } returns mockk(relaxed = true)
                PlaylistMirrorJobs(mockk(), store, coordinator, accounts, registry).start(backgroundScope)
                runCurrent()
                installed.value = installed.value.copy(plugins = listOf(source, plugin("public")))
                enabled.value = setOf("source|missing-target", "missing-source|missing-target")
                runCurrent()
                assertEquals(ProviderAccount.SignedIn("listener-1"), accounts.accounts.value["source"])

                val replacement = plugin("source", true)
                every { replacement.manifest.signIn } returns listOf(mockk<SignInMethod>())
                installed.value = installed.value.copy(plugins = listOf(replacement, plugin("public")))
                runCurrent()
                assertEquals(ProviderAccount.SignedIn("listener-2"), accounts.accounts.value["source"])
            } finally {
                unmockkObject(WorkManager.Companion)
            }
        }

    @Test
    fun `pair removal leaves other schedules intact and plugin replacement immediately refreshes its own pair`() =
        runTest {
            val installed =
                MutableStateFlow(PluginRegistryState(listOf(plugin("source-a", true), plugin("source-b", true), plugin("target"))))
            val known =
                MutableStateFlow<Map<String, ProviderAccount>>(
                    mapOf(
                        "source-a" to ProviderAccount.SignedIn("a"),
                        "source-b" to ProviderAccount.SignedIn("b"),
                        "target" to ProviderAccount.SignedIn("t"),
                    ),
                )
            val enabled =
                MutableStateFlow(setOf(PlaylistMirrorStore.pairId("source-a", "target"), PlaylistMirrorStore.pairId("source-b", "target")))
            val store = mockk<PlaylistMirrorStore> { every { enabledPairs } returns enabled }
            val registry = mockk<PluginRegistry> { every { state } returns installed }
            val accounts = mockk<PluginAccounts> { every { this@mockk.accounts } returns known }
            coEvery { accounts.refresh(any()) } coAnswers { known.value.getValue(firstArg()) }
            val coordinator = PlaylistMirrorCoordinator(mockk(), store, registry, accounts, MirrorExecutionGate())
            val a = coordinator.key("source-a", "target", EntityRef(EntityKind.PLAYLIST, "owned"))!!
            val b = coordinator.key("source-b", "target", EntityRef(EntityKind.PLAYLIST, "owned"))!!
            val work = mockk<WorkManager>(relaxed = true)
            mockkObject(WorkManager.Companion)
            try {
                every { WorkManager.getInstance(any<Context>()) } returns work
                PlaylistMirrorJobs(mockk(), store, coordinator, accounts, registry).start(backgroundScope)
                runCurrent()
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${a.id}", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${b.id}", ExistingWorkPolicy.KEEP, any<OneTimeWorkRequest>()) }

                installed.value =
                    installed.value.copy(
                        plugins =
                            installed.value.plugins.map {
                                if (it.id == "source-a") plugin("source-a", true) else it
                            },
                    )
                runCurrent()
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${a.id}", ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${b.id}", any(), any<OneTimeWorkRequest>()) }
                verify(exactly = 1) { work.enqueueUniquePeriodicWork("mirror-refresh:${b.id}", ExistingPeriodicWorkPolicy.KEEP, any()) }

                enabled.value = setOf(PlaylistMirrorStore.pairId("source-b", "target"))
                runCurrent()
                verify(exactly = 1) { work.cancelAllWorkByTag("mirror:${a.id}") }
                verify(exactly = 0) { work.cancelAllWorkByTag("mirror:${b.id}") }
                verify(exactly = 1) { work.enqueueUniqueWork("mirror-now:${b.id}", any(), any<OneTimeWorkRequest>()) }
                verify(exactly = 1) { work.enqueueUniquePeriodicWork("mirror-refresh:${b.id}", ExistingPeriodicWorkPolicy.KEEP, any()) }
            } finally {
                unmockkObject(WorkManager.Companion)
            }
        }

    private fun plugin(
        id: String,
        source: Boolean = false,
    ): InstalledPlugin =
        mockk<InstalledPlugin>(relaxed = true) {
            every { this@mockk.id } returns id
            every { enabled } returns true
            every { manifest.roles.metadata } returns
                MetadataRole(
                    emptySet(),
                    setOf(EntityKind.PLAYLIST),
                    id,
                    personalCollections = source,
                    privatePlaylistImport = !source,
                )
            every { manifest.roles.audio } returns if (source) null else AudioRole(setOf(id))
        }
}
