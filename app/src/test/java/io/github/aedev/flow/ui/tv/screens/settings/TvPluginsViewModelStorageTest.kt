package io.github.aedev.flow.ui.tv.screens.settings

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.host.PluginConnections
import io.github.aedev.flow.plugin.install.PluginAutoUpdater
import io.github.aedev.flow.plugin.install.PluginLinks
import io.github.aedev.flow.plugin.install.PluginUpdatesState
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.ConnectionManifest
import nl.neerdael.milkbeat.plugin.PluginConnectionRow
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TvPluginsViewModelStorageTest {
    private val dispatcher = StandardTestDispatcher()
    private val registry = mockk<PluginRegistry>(relaxed = true) { every { state } returns MutableStateFlow(PluginRegistryState()) }
    private val accounts = mockk<PluginAccounts>(relaxed = true) { every { accounts } returns MutableStateFlow(emptyMap()) }
    private val updater = mockk<PluginAutoUpdater>(relaxed = true) { every { state } returns flowOf(PluginUpdatesState.Idle) }
    private val links = mockk<PluginLinks>(relaxed = true) { every { pending } returns MutableStateFlow(null) }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel(connections: PluginConnections = mockk(relaxed = true)) =
        TvPluginsViewModel(
            registry,
            mockk(relaxed = true),
            updater,
            accounts,
            mockk(relaxed = true),
            connections,
            mockk(relaxed = true),
            links,
            mockk(relaxed = true),
            SavedStateHandle(),
            mockk(relaxed = true),
            mockk(relaxed = true),
        )

    @Test
    fun `a stale manifest inspection cannot reopen the review after leaving the editor`() =
        runTest(dispatcher) {
            val plugin =
                InstalledPlugin(
                    PluginManifest(1, ApiRange(1, 2), "eclipse", "Eclipse", "1.0", 1, roles = Roles()),
                    "signer",
                    "https://example.test/eclipse",
                    0,
                    emptyList(),
                    emptyList(),
                )
            every { registry.state } returns MutableStateFlow(PluginRegistryState(plugins = listOf(plugin)))
            val inspection = CompletableDeferred<ConnectionManifest>()
            val connections = mockk<PluginConnections>(relaxed = true)
            coEvery { connections.list("eclipse") } returns emptyList()
            coEvery { connections.inspect(any()) } coAnswers { inspection.await() }
            val vm = viewModel(connections)
            backgroundScope.launch { vm.state.collect {} }

            vm.openConnections("eclipse")
            runCurrent()
            vm.editConnectionUrl("row-1")
            runCurrent()
            vm.reviewConnection("https://addon.test/manifest.json")
            runCurrent()
            // Back leaves the editor while the manifest is still being inspected.
            vm.showConnectionList()
            runCurrent()

            inspection.complete(ConnectionManifest("https://addon.test/manifest.json", "addon"))
            runCurrent()

            assertThat(vm.state.value.connections.page).isEqualTo(ConnectionsPage.List)
            assertThat(vm.state.value.connections.busy).isFalse()
        }

    @Test
    fun `a failed add-on removal keeps its error after the refresh`() =
        runTest(dispatcher) {
            val plugin =
                InstalledPlugin(
                    PluginManifest(1, ApiRange(1, 2), "eclipse", "Eclipse", "1.0", 1, roles = Roles()),
                    "signer",
                    "https://example.test/eclipse",
                    0,
                    emptyList(),
                    emptyList(),
                )
            every { registry.state } returns MutableStateFlow(PluginRegistryState(plugins = listOf(plugin)))
            val connections = mockk<PluginConnections>(relaxed = true)
            coEvery { connections.list("eclipse") } returns emptyList()
            coEvery { connections.remove(plugin, any()) } throws IOException("no space left")
            val vm = viewModel(connections)
            backgroundScope.launch { vm.state.collect {} }

            vm.openConnections("eclipse")
            runCurrent()
            vm.removeConnection(mockk<PluginConnectionRow>(relaxed = true))
            runCurrent()

            assertThat(vm.state.value.connections.failure).isEqualTo("no space left")
        }

    @Test
    fun `a sign-out or removal the storage cannot save is reported instead of crashing`() =
        runTest(dispatcher) {
            coEvery { accounts.signOut("dev.youtube") } throws IOException("no space left")
            coEvery { accounts.removing("dev.youtube", any()) } throws IOException("no space left")
            val vm = viewModel()
            backgroundScope.launch { vm.state.collect {} }

            vm.signOut("dev.youtube")
            runCurrent()
            assertThat((vm.state.value.adding as AddPluginState.Failed).messageResource).isEqualTo(R.string.tv_plugins_storage_failed)

            vm.cancelAdd()
            vm.remove("dev.youtube")
            runCurrent()
            assertThat((vm.state.value.adding as AddPluginState.Failed).messageResource).isEqualTo(R.string.tv_plugins_storage_failed)
        }
}
