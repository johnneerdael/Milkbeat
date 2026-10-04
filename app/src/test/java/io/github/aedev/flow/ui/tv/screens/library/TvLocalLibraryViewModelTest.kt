package io.github.aedev.flow.ui.tv.screens.library

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.ProviderAccount
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvLocalLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val revision = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("local:1"))
    private val local =
        mockk<LocalCatalogProvider> {
            every { account } returns revision
            every { hasTracks } returns flowOf(true)
            coEvery { home(any()) } answers { Result.success(MetadataPage("home:${firstArg<HomeRequest>().filterId}", emptyList())) }
        }
    private val registry = mockk<PluginRegistry> { every { state } returns MutableStateFlow(PluginRegistryState()) }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun theLibraryLoadsOnlyWhileShownAndAgainWhenTheIndexChanges() =
        runTest(dispatcher) {
            val vm = TvLocalLibraryViewModel(local, registry)
            advanceUntilIdle()
            coVerify(exactly = 0) { local.home(any()) }

            val shown = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect {} }
            advanceUntilIdle()
            coVerify(exactly = 1) { local.home(HomeRequest(filterId = null)) }

            revision.value = ProviderAccount.SignedIn("local:2")
            advanceUntilIdle()
            coVerify(exactly = 2) { local.home(HomeRequest(filterId = null)) }

            vm.selectFilter(FilterOption("Techno", "Techno"))
            advanceUntilIdle()
            assertThat(vm.state.value.selectedFilterId).isEqualTo("Techno")

            shown.cancel()
            dispatcher.scheduler.advanceTimeBy(10_000)
            revision.value = ProviderAccount.SignedIn("local:3")
            advanceUntilIdle()
            coVerify(exactly = 1) { local.home(HomeRequest(filterId = "Techno")) }
        }
}
