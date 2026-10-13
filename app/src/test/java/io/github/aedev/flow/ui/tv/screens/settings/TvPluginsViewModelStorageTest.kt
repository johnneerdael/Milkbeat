package io.github.aedev.flow.ui.tv.screens.settings

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.install.PluginAutoUpdater
import io.github.aedev.flow.plugin.install.PluginLinks
import io.github.aedev.flow.plugin.install.PluginUpdatesState
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorCoordinator
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
    private val mirrors =
        mockk<PlaylistMirrorCoordinator>(relaxed = true) { every { store.enabledPairs } returns flowOf(emptySet()) }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() =
        TvPluginsViewModel(
            registry,
            mockk(relaxed = true),
            updater,
            accounts,
            mockk(relaxed = true),
            mockk(relaxed = true),
            links,
            mockk(relaxed = true),
            SavedStateHandle(),
            mirrors,
            mockk(relaxed = true),
        )

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
