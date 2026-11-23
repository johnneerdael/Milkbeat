package io.github.aedev.flow.ui.tv.screens.search

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.local.SearchHistoryRepository
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.SearchRequest
import nl.neerdael.milkbeat.catalog.Suggestions
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import org.junit.After
import org.junit.Before
import org.junit.Test

/** A provider that answers `UNSUPPORTED` — Eclipse without Pulsar or Gravity — leaves the chips. */
@OptIn(ExperimentalCoroutinesApi::class)
class TvSearchUnsupportedSourceTest {
    private val dispatcher = StandardTestDispatcher()
    private val history =
        mockk<SearchHistoryRepository>(relaxed = true) {
            every { getSearchHistoryFlow() } returns flowOf(emptyList())
            coEvery { isSearchHistoryEnabled() } returns true
        }
    private val stats = mockk<VideoStatsRecorder>(relaxed = true)
    private val music = Backend()

    private class Backend(
        var pages: suspend (SearchRequest) -> Result<MetadataPage> = { Result.success(page(it.query)) },
    ) : TvSearchBackend {
        override suspend fun search(request: SearchRequest): Result<MetadataPage> = pages(request)

        override suspend fun suggest(query: String): Result<Suggestions> = Result.success(Suggestions(emptyList()))
    }

    private fun viewModel() =
        TvSearchViewModel(
            SavedStateHandle(),
            { music },
            { _, _ -> null },
            history,
            stats,
        )

    private fun unsupported(): Result<MetadataPage> =
        Result.failure(
            PluginCallException(
                "nl.neerdael.eclipse",
                PluginError(PluginErrorCode.UNSUPPORTED, "Add Pulsar or Gravity to search from Eclipse"),
            ),
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `a provider that cannot search is dropped from the chips`() =
        runTest(dispatcher) {
            music.pages = { unsupported() }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("anyma")
            advanceUntilIdle()

            assertThat(vm.unsupported.value).containsExactly(MUSIC.key)
            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .error,
            ).isNotNull()
        }

    @Test
    fun `an ordinary failure keeps the provider in the chips`() =
        runTest(dispatcher) {
            music.pages = { Result.failure(IllegalStateException("The network said no")) }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("anyma")
            advanceUntilIdle()

            assertThat(vm.unsupported.value).isEmpty()
            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .error,
            ).isEqualTo("The network said no")
        }

    @Test
    fun `a changed provider identity brings the dropped source back`() =
        runTest(dispatcher) {
            music.pages = { unsupported() }
            val vm = viewModel()
            vm.retainSources(mapOf(MUSIC.key to "install-1"))
            vm.showSource(MUSIC)
            vm.onQueryChange("anyma")
            advanceUntilIdle()
            assertThat(vm.unsupported.value).containsExactly(MUSIC.key)

            // Pulsar connected: the plugin's identity moved, so the chip is offered again.
            vm.retainSources(mapOf(MUSIC.key to "install-2"))
            assertThat(vm.unsupported.value).isEmpty()
        }

    @Test
    fun `a successful answer clears an earlier unsupported mark`() =
        runTest(dispatcher) {
            music.pages = { unsupported() }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("anyma")
            advanceUntilIdle()
            assertThat(vm.unsupported.value).containsExactly(MUSIC.key)

            music.pages = { Result.success(page(it.query)) }
            vm.onQueryChange("shut down")
            advanceUntilIdle()
            assertThat(vm.unsupported.value).isEmpty()
            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .blocks,
            ).isNotEmpty()
        }

    private companion object {
        val MUSIC = MusicSource.Plugin("eclipse")

        fun page(query: String) =
            MetadataPage(
                id = "search/$query",
                blocks =
                    listOf(
                        CollectionBlock(
                            id = "results",
                            header = null,
                            layout = CollectionLayout.TRACK_TABLE,
                            defaultItemView = ItemView.TRACK_ROW,
                            items =
                                listOf(
                                    MetadataItem(
                                        id = "$query-1",
                                        entity = EntityRef(EntityKind.TRACK, "$query-1"),
                                        title = "$query-1",
                                    ),
                                ),
                        ),
                    ),
            )
    }
}
