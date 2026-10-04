package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.library.catalog.LocalLibraryEmptyException
import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionHeader
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.ProviderAccount
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicHomeFeedViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val provider = FakeProvider()

    private fun page(
        vararg titles: String,
        cursor: String? = null,
        filters: List<FilterOption>? = null,
    ) = MetadataPage(
        id = "home",
        blocks =
            titles.map {
                CollectionBlock(
                    id = it,
                    header = CollectionHeader(it),
                    layout = CollectionLayout.HORIZONTAL_SHELF,
                    defaultItemView = ItemView.COVER_CARD,
                    items = emptyList(),
                )
            },
        filters = filters?.let(::FilterControl),
        nextCursor = cursor,
    )

    private val MusicHomeFeedViewModel.titles: List<String?>
        get() = state.value.blocks.map { (it as CollectionBlock).header?.title }

    private fun viewModel() = MusicHomeFeedViewModel(provider, CatalogPlayback { null })

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `every continuation page is followed and appended in order`() =
        runTest(dispatcher) {
            provider.pages = { request ->
                when (request.cursor) {
                    null -> Result.success(page("Take it easy", "Long listens", cursor = "c1"))
                    "c1" -> Result.success(page("Forgotten favorites", "Quick picks", cursor = "c2"))
                    else -> Result.success(page("Covers and remixes"))
                }
            }
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()

            assertThat(vm.titles)
                .containsExactly("Take it easy", "Long listens", "Forgotten favorites", "Quick picks", "Covers and remixes")
                .inOrder()
            assertThat(vm.state.value.isLoading).isFalse()
            assertThat(vm.state.value.isLoadingMore).isFalse()
        }

    @Test
    fun `a repeated continuation token ends the walk instead of looping`() =
        runTest(dispatcher) {
            provider.pages = { request ->
                if (request.cursor ==
                    null
                ) {
                    Result.success(page("First", cursor = "loop"))
                } else {
                    Result.success(page("Second", cursor = "loop"))
                }
            }
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()

            assertThat(vm.titles).containsExactly("First", "Second").inOrder()
            assertThat(provider.requests.count { it.cursor == "loop" }).isEqualTo(1)
        }

    @Test
    fun `a block served twice is shown once`() =
        runTest(dispatcher) {
            provider.pages = { request ->
                val served = if (request.cursor == null) page("Quick picks", cursor = "c1") else page("Quick picks", "New releases")
                Result.success(served)
            }
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()

            assertThat(vm.titles).containsExactly("Quick picks", "New releases").inOrder()
        }

    @Test
    fun `two different shelves with one title are both kept, under distinct ids`() =
        runTest(dispatcher) {
            val first = page("Recommended music videos", cursor = "c1")
            val second =
                page("Recommended music videos").let { served ->
                    served.copy(blocks = served.blocks.map { (it as CollectionBlock).copy(defaultItemView = ItemView.LANDSCAPE_CARD) })
                }
            provider.pages = { request -> Result.success(if (request.cursor == null) first else second) }
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()

            assertThat(vm.titles).containsExactly("Recommended music videos", "Recommended music videos")
            assertThat(
                vm.state.value.blocks
                    .map { it.id }
                    .toSet(),
            ).hasSize(2)
        }

    @Test
    fun `the home loads once within its freshness window`() =
        runTest(dispatcher) {
            provider.pages = { Result.success(page("Once")) }
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            vm.load()
            advanceUntilIdle()

            assertThat(provider.requests).hasSize(1)
        }

    @Test
    fun `a filter reloads the home with it and selecting it again clears it`() =
        runTest(dispatcher) {
            val relax = FilterOption(id = "relax", label = "Relax")
            provider.pages = { request ->
                if (request.filterId == "relax") Result.success(page("Calm")) else Result.success(page("Default", filters = listOf(relax)))
            }
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            vm.selectFilter(relax)
            advanceUntilIdle()
            assertThat(vm.state.value.selectedFilterId).isEqualTo("relax")
            assertThat(vm.state.value.filters).containsExactly(relax)
            assertThat(vm.titles).containsExactly("Calm")

            vm.selectFilter(relax)
            advanceUntilIdle()
            assertThat(vm.state.value.selectedFilterId).isNull()
            assertThat(vm.titles).containsExactly("Default")
        }

    @Test
    fun `another account swaps the home for that account's`() =
        runTest(dispatcher) {
            provider.pages = {
                if (provider.current is ProviderAccount.SignedIn) Result.success(page("Mine")) else Result.success(page("Anonymous"))
            }
            val vm = viewModel()
            vm.load()
            advanceUntilIdle()

            provider.current = ProviderAccount.SignedIn("account-1")
            advanceUntilIdle()

            assertThat(vm.titles).containsExactly("Mine")
        }

    @Test
    fun `an expired account is reported and its home reloaded`() =
        runTest(dispatcher) {
            provider.current = ProviderAccount.SignedIn("account-1")
            provider.pages = {
                if (provider.current is ProviderAccount.SignedIn) Result.success(page("Mine")) else Result.success(page("Anonymous"))
            }
            val vm = viewModel()
            backgroundScope.launch { vm.isAccountExpired.collect {} }
            vm.load()
            advanceUntilIdle()

            provider.current = ProviderAccount.Expired
            advanceUntilIdle()

            assertThat(vm.isAccountExpired.value).isTrue()
            assertThat(vm.titles).containsExactly("Anonymous")
        }

    @Test
    fun `without a music plugin the page asks for one, and choosing one loads its home`() =
        runTest(dispatcher) {
            provider.id = "none"
            provider.pages = { if (provider.id == "none") Result.failure(NoMetadataPluginException()) else Result.success(page("Home")) }
            val vm = viewModel()
            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.needsPlugin).isTrue()

            provider.id = "dev.example.music"
            provider.current = ProviderAccount.Anonymous
            advanceUntilIdle()

            assertThat(vm.state.value.needsPlugin).isFalse()
            assertThat(vm.titles).containsExactly("Home")
        }

    @Test
    fun `an empty local library asks for a scan and fills in when the index changes`() =
        runTest(dispatcher) {
            var indexed = false
            provider.pages =
                { if (indexed) Result.success(page("Recently added")) else Result.failure(LocalLibraryEmptyException("Indexing")) }
            val vm = viewModel()
            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.libraryEmpty).isTrue()
            assertThat(vm.state.value.needsPlugin).isFalse()

            indexed = true
            provider.current = ProviderAccount.SignedIn("local:revision-2")
            advanceUntilIdle()

            assertThat(vm.state.value.libraryEmpty).isFalse()
            assertThat(vm.titles).containsExactly("Recently added")
        }

    @Test
    fun `a failed first page is shown as an error and retried on the next visit`() =
        runTest(dispatcher) {
            var online = false
            provider.pages = { if (online) Result.success(page("Recovered")) else Result.failure(IllegalStateException("offline")) }
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.error).isEqualTo("offline")

            online = true
            vm.load()
            advanceUntilIdle()
            assertThat(vm.titles).containsExactly("Recovered")
        }

    @Test
    fun `a superseded load never writes over the load that replaced it`() =
        runTest(dispatcher) {
            val relax = FilterOption(id = "relax", label = "Relax")
            val stalled = CompletableDeferred<Unit>()
            // Mirrors a provider that runCatching-wraps its request: the cancellation surfaces as a failed
            // Result, and only after the replacing load has finished (the HTTP call winds down first).
            provider.pages = { request ->
                if (request.filterId == "relax") {
                    Result.success(page("Calm"))
                } else {
                    runCatching {
                        try {
                            stalled.await()
                            page("Old")
                        } finally {
                            withContext(NonCancellable) { delay(1_000) }
                        }
                    }
                }
            }
            val vm = viewModel()

            vm.load()
            advanceUntilIdle()
            vm.selectFilter(relax)
            advanceUntilIdle()

            assertThat(vm.state.value.error).isNull()
            assertThat(vm.state.value.isLoading).isFalse()
            assertThat(vm.titles).containsExactly("Calm")
        }

    @Test
    fun `a refresh keeps the current blocks until the new first page arrives`() =
        runTest(dispatcher) {
            val refreshed = CompletableDeferred<Result<MetadataPage>>()
            var calls = 0
            provider.pages = { if (calls++ == 0) Result.success(page("Before")) else refreshed.await() }
            val vm = viewModel()
            vm.load()
            advanceUntilIdle()

            vm.load(force = true)
            advanceUntilIdle()
            assertThat(vm.titles).containsExactly("Before")
            assertThat(vm.state.value.isLoading).isTrue()

            refreshed.complete(Result.success(page("After")))
            advanceUntilIdle()
            assertThat(vm.titles).containsExactly("After")
        }

    private class FakeProvider : MetadataProvider {
        override var id: String = "fake"

        // Like the plugin provider, it emits an equal account again when another provider is chosen.
        override val account = MutableSharedFlow<ProviderAccount>(replay = 1).apply { tryEmit(ProviderAccount.Anonymous) }
        var current: ProviderAccount
            get() = account.replayCache.last()
            set(value) {
                account.tryEmit(value)
            }
        var pages: suspend (HomeRequest) -> Result<MetadataPage> = { Result.failure(IllegalStateException("no page")) }
        val requests = mutableListOf<HomeRequest>()

        override suspend fun home(request: HomeRequest): Result<MetadataPage> {
            requests += request
            return pages(request)
        }

        override suspend fun page(
            entity: EntityRef,
            cursor: String?,
        ): Result<MetadataPage> = Result.failure(UnsupportedOperationException())
    }
}
