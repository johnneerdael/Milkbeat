package io.github.aedev.flow.ui.tv.screens.search

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.local.SearchHistoryRepository
import io.github.aedev.flow.data.local.SearchType
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.SearchRequest
import nl.neerdael.milkbeat.catalog.Suggestions
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvSearchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val history =
        mockk<SearchHistoryRepository>(relaxed = true) {
            every { getSearchHistoryFlow() } returns flowOf(emptyList())
            coEvery { isSearchHistoryEnabled() } returns true
        }
    private val stats = mockk<VideoStatsRecorder>(relaxed = true)

    private class FakeBackend(
        var pages: suspend (SearchRequest) -> Result<MetadataPage> = { Result.success(page(it.query)) },
        var typeahead: suspend (String) -> Result<Suggestions> = { Result.success(Suggestions(emptyList())) },
    ) : TvSearchBackend {
        val searches = mutableListOf<SearchRequest>()
        val suggests = mutableListOf<String>()

        override suspend fun search(request: SearchRequest): Result<MetadataPage> {
            searches += request
            return pages(request)
        }

        override suspend fun suggest(query: String): Result<Suggestions> {
            suggests += query
            return typeahead(query)
        }
    }

    private val music = FakeBackend()
    private val local = FakeBackend()

    private fun viewModel(query: String? = null) =
        TvSearchViewModel(
            SavedStateHandle(listOfNotNull(query?.let { "query" to it }).toMap()),
            { source -> if (source == MusicSource.Local) local else music },
            { _, _ -> null },
            history,
            stats,
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
    fun `typing searches the chip on screen once, after the pause, and asks only it for typeahead`() =
        runTest(dispatcher) {
            music.typeahead = { Result.success(Suggestions(listOf("$it live"))) }
            val vm = viewModel()
            vm.showSource(MUSIC)

            vm.onQueryChange("ca")
            advanceTimeBy(100)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            assertThat(music.searches).containsExactly(SearchRequest("cafe"))
            assertThat(music.suggests).containsExactly("cafe")
            assertThat(local.searches).isEmpty()
            assertThat(local.suggests).isEmpty()
            val state = vm.state.value
            assertThat(state.results(MUSIC).blocks).isEqualTo(page("cafe").blocks)
            assertThat(state.results(MUSIC).filters.map { it.id }).containsExactly("songs", "albums").inOrder()
            assertThat(state.suggestions).containsExactly("cafe live")
        }

    @Test
    fun `nothing searches or suggests before a chip is shown, and showing one asks it for the query`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.onQueryChange("cafe")
            vm.selectFilter("songs")
            vm.showAll("albums")
            advanceUntilIdle()

            assertThat(music.searches).isEmpty()
            assertThat(music.suggests).isEmpty()
            assertThat(vm.track(item("a"))).isNull()

            vm.showSource(MUSIC)
            advanceUntilIdle()

            assertThat(music.searches).containsExactly(SearchRequest("cafe"))
            assertThat(music.suggests).containsExactly("cafe")
        }

    @Test
    fun `a filter searches with its id, and picking it again goes back to the mixed results`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.selectFilter("songs")
            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .filterId,
            ).isEqualTo("songs")
            advanceUntilIdle()
            vm.showAll("songs")
            advanceUntilIdle()
            vm.selectFilter("songs")
            advanceUntilIdle()

            assertThat(music.searches)
                .containsExactly(SearchRequest("cafe"), SearchRequest("cafe", "songs"), SearchRequest("cafe"))
                .inOrder()
            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .filterId,
            ).isNull()
        }

    @Test
    fun `the next page extends the results once, however often the end comes into view`() =
        runTest(dispatcher) {
            music.pages = { request ->
                Result.success(
                    if (request.cursor == null) {
                        MetadataPage("p", listOf(results("a", "b")), nextCursor = "c1")
                    } else {
                        MetadataPage("p", listOf(results("b", "c")), nextCursor = null)
                    },
                )
            }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.loadMore(MUSIC)
            vm.loadMore(MUSIC)
            advanceUntilIdle()
            vm.loadMore(MUSIC)
            advanceUntilIdle()

            assertThat(music.searches).containsExactly(SearchRequest("cafe"), SearchRequest("cafe", cursor = "c1")).inOrder()
            val block =
                vm.state.value
                    .results(MUSIC)
                    .blocks
                    .single() as CollectionBlock
            assertThat(block.items.map { it.id }).containsExactly("a", "b", "c").inOrder()
            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .nextCursor,
            ).isNull()
        }

    @Test
    fun `a newer query cancels the older one, whose answer never shows`() =
        runTest(dispatcher) {
            music.pages = { request ->
                if (request.query == "old") delay(5_000)
                Result.success(page(request.query))
            }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("old")
            advanceTimeBy(1_000)
            vm.onQueryChange("new")
            advanceUntilIdle()

            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .query,
            ).isEqualTo("new")
            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .blocks,
            ).isEqualTo(page("new").blocks)
        }

    @Test
    fun `no chosen plugin says so, and a failing provider gives its reason`() =
        runTest(dispatcher) {
            music.pages = { Result.failure(NoMetadataPluginException()) }
            local.pages = { Result.failure(IllegalStateException("The library said no")) }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()
            vm.showSource(LOCAL)
            advanceUntilIdle()

            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .noPlugin,
            ).isTrue()
            assertThat(
                vm.state.value
                    .results(LOCAL)
                    .noPlugin,
            ).isFalse()
            assertThat(
                vm.state.value
                    .results(LOCAL)
                    .error,
            ).isEqualTo("The library said no")
        }

    @Test
    fun `clearing the query drops results and typeahead but keeps the filters`() =
        runTest(dispatcher) {
            music.typeahead = { Result.success(Suggestions(listOf("$it live"))) }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()
            vm.onQueryChange("")
            advanceUntilIdle()

            val state = vm.state.value
            assertThat(state.results(MUSIC).blocks).isEmpty()
            assertThat(state.results(MUSIC).filters).isNotEmpty()
            assertThat(state.suggestions).isEmpty()
        }

    @Test
    fun `a restored query searches when its chip is shown, and a picked suggestion is saved`() =
        runTest(dispatcher) {
            val vm = viewModel(query = "cafe")
            vm.showSource(MUSIC)
            advanceUntilIdle()
            vm.submit("cafe del mar", SearchType.SUGGESTION)
            advanceUntilIdle()

            assertThat(music.searches).containsExactly(SearchRequest("cafe"), SearchRequest("cafe del mar")).inOrder()
            coVerify { history.saveSearchQuery("cafe del mar", SearchType.SUGGESTION) }
        }

    @Test
    fun `each music chip searches its own provider once and keeps its own results`() =
        runTest(dispatcher) {
            local.pages = { Result.success(page("local ${it.query}")) }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.showSource(LOCAL)
            advanceUntilIdle()
            vm.showSource(MUSIC)
            vm.showSource(LOCAL)
            advanceUntilIdle()

            assertThat(music.searches).containsExactly(SearchRequest("cafe"))
            assertThat(local.searches).containsExactly(SearchRequest("cafe"))
            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .blocks,
            ).isEqualTo(page("cafe").blocks)
            assertThat(
                vm.state.value
                    .results(LOCAL)
                    .blocks,
            ).isEqualTo(page("local cafe").blocks)
        }

    @Test
    fun `a chip that goes away and comes back searches its provider again`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.retainSources(mapOf(LOCAL.key to null))
            vm.showSource(MUSIC)
            advanceUntilIdle()

            assertThat(music.searches).containsExactly(SearchRequest("cafe"), SearchRequest("cafe"))
        }

    @Test
    fun `switching to another music chip asks it for typeahead on the current query`() =
        runTest(dispatcher) {
            local.typeahead = { Result.success(Suggestions(listOf("$it local"))) }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.showSource(LOCAL)
            advanceUntilIdle()

            assertThat(local.suggests).containsExactly("cafe")
            assertThat(vm.state.value.suggestions).containsExactly("cafe local")
        }

    @Test
    fun `a chip without typeahead shows no suggestions from the chip before it`() =
        runTest(dispatcher) {
            music.typeahead = { Result.success(Suggestions(listOf("$it sphere"))) }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("anyma")
            advanceUntilIdle()
            assertThat(vm.state.value.suggestions).containsExactly("anyma sphere")

            vm.showSource(LOCAL)
            assertThat(vm.state.value.suggestions).isEmpty()
            advanceUntilIdle()

            assertThat(vm.state.value.suggestions).isEmpty()
        }

    @Test
    fun `switching music chips while typeahead is pending asks only the new chip, once`() =
        runTest(dispatcher) {
            music.typeahead = { Result.success(Suggestions(listOf("$it spotify"))) }
            local.typeahead = { Result.success(Suggestions(listOf("$it local"))) }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceTimeBy(100)

            vm.showSource(LOCAL)
            advanceUntilIdle()

            assertThat(music.suggests).isEmpty()
            assertThat(local.suggests).containsExactly("cafe")
            assertThat(vm.state.value.suggestions).containsExactly("cafe local")
        }

    @Test
    fun `a chip that goes away takes its typeahead with it, and nothing asks until another is shown`() =
        runTest(dispatcher) {
            music.typeahead = { Result.success(Suggestions(listOf("$it spotify"))) }
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()
            assertThat(vm.state.value.suggestions).containsExactly("cafe spotify")

            vm.retainSources(mapOf(LOCAL.key to null))
            vm.onQueryChange("cafe del")
            advanceUntilIdle()

            assertThat(vm.state.value.suggestions).isEmpty()
            assertThat(music.suggests).containsExactly("cafe")
            assertThat(music.searches).containsExactly(SearchRequest("cafe"))
            assertThat(local.searches).isEmpty()
        }

    @Test
    fun `a chip that goes away during the typing pause never searches`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceTimeBy(100)

            vm.retainSources(mapOf(LOCAL.key to null))
            advanceUntilIdle()

            assertThat(music.searches).isEmpty()
            assertThat(music.suggests).isEmpty()
            assertThat(vm.state.value.results).doesNotContainKey(MUSIC.key)
        }

    @Test
    fun `a chip whose provider identity changes searches and suggests afresh`() =
        runTest(dispatcher) {
            music.typeahead = { Result.success(Suggestions(listOf("$it spotify"))) }
            val vm = viewModel()
            vm.retainSources(mapOf(MUSIC.key to "account-1"))
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.retainSources(mapOf(MUSIC.key to "anonymous"))
            advanceUntilIdle()

            // The chip on screen searches again by itself; showing it again asks nothing more.
            assertThat(music.searches).containsExactly(SearchRequest("cafe"), SearchRequest("cafe"))
            assertThat(music.suggests).containsExactly("cafe", "cafe")
            assertThat(
                vm.state.value
                    .results(MUSIC)
                    .loaded,
            ).isTrue()
            vm.showSource(MUSIC)
            advanceUntilIdle()
            assertThat(music.searches).hasSize(2)
        }

    @Test
    fun `the chip on screen keeps answering typeahead after its identity changes`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.retainSources(mapOf(MUSIC.key to "account-1"))
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            vm.retainSources(mapOf(MUSIC.key to "anonymous"))
            advanceUntilIdle()
            vm.onQueryChange("cafe del")
            advanceUntilIdle()

            assertThat(music.suggests).contains("cafe del")
        }

    @Test
    fun `a typeahead from before an identity change never replaces the new account's`() =
        runTest(dispatcher) {
            var account = "account-1"
            music.typeahead = { query ->
                val asked = account
                delay(if (asked == "account-1") 2_000 else 100)
                Result.success(Suggestions(listOf("$query $asked")))
            }
            val vm = viewModel()
            vm.retainSources(mapOf(MUSIC.key to "account-1"))
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceTimeBy(500)

            account = "account-2"
            vm.retainSources(mapOf(MUSIC.key to "account-2"))
            advanceUntilIdle()

            assertThat(vm.state.value.suggestions).containsExactly("cafe account-2")
        }

    @Test
    fun `leaving a chip during its typing pause stops its search, and coming back searches it`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceTimeBy(100)

            vm.showSource(LOCAL)
            advanceUntilIdle()

            assertThat(music.searches).isEmpty()
            assertThat(local.searches).containsExactly(SearchRequest("cafe"))

            vm.showSource(MUSIC)
            advanceUntilIdle()
            assertThat(music.searches).containsExactly(SearchRequest("cafe"))
        }

    @Test
    fun `typeahead asks only the chip on screen, not the chips shown before it`() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.showSource(LOCAL)
            vm.showSource(MUSIC)
            vm.onQueryChange("cafe")
            advanceUntilIdle()

            assertThat(music.suggests).containsExactly("cafe")
            assertThat(local.suggests).isEmpty()
        }

    private companion object {
        val MUSIC = MusicSource.Plugin("spotify")
        val LOCAL = MusicSource.Local

        fun item(id: String) = MetadataItem(id = id, entity = EntityRef(EntityKind.VIDEO, id), title = id)

        fun results(vararg ids: String) =
            CollectionBlock(
                id = "results",
                header = null,
                layout = CollectionLayout.HORIZONTAL_SHELF,
                defaultItemView = ItemView.LANDSCAPE_CARD,
                items = ids.map(::item),
            )

        fun page(query: String) =
            MetadataPage(
                id = "search/$query",
                blocks = listOf(results("$query-1", "$query-2")),
                filters = FilterControl(listOf(FilterOption("songs", "Songs"), FilterOption("albums", "Albums"))),
            )
    }
}
