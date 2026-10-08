package io.github.aedev.flow.ui.screens.music

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.library.catalog.LocalCatalogProvider
import io.github.aedev.flow.data.library.catalog.LocalLibraryEmptyException
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.plugin.runtime.TransientRetryBackoffMs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionHeader
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
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

    /** Collects the state as the shown screen does; cancel the job to leave the tab. */
    private fun TestScope.show(vm: MusicHomeFeedViewModel): Job = backgroundScope.launch { vm.state.collect {} }

    private fun pluginFailure(
        code: PluginErrorCode,
        retryAfterMs: Long? = null,
    ) = PluginCallException(
        "dev.example.spotify",
        PluginError(code, "$code Spotify returned HTTP 500", retryAfterMs = retryAfterMs),
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
            show(vm)
            vm.load()
            advanceUntilIdle()

            provider.current = ProviderAccount.SignedIn("account-1")
            advanceUntilIdle()

            assertThat(vm.titles).containsExactly("Mine")
        }

    @Test
    fun `a hidden tab fetches nothing when its account changes, and loads the new account's home when shown`() =
        runTest(dispatcher) {
            var fetches = 0
            provider.pages = {
                fetches++
                if (provider.current is ProviderAccount.SignedIn) Result.success(page("Mine")) else Result.success(page("Anonymous"))
            }
            val vm = viewModel()
            val shown = show(vm)
            vm.load()
            advanceUntilIdle()
            shown.cancelAndJoin()
            advanceUntilIdle()

            provider.current = ProviderAccount.SignedIn("account-1")
            advanceUntilIdle()

            assertThat(fetches).isEqualTo(1)

            show(vm)
            runCurrent()
            vm.load()
            advanceUntilIdle()

            assertThat(fetches).isEqualTo(2)
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
            show(vm)
            backgroundScope.launch { vm.isAccountExpired.collect {} }
            vm.load()
            advanceUntilIdle()

            provider.current = ProviderAccount.Expired
            advanceUntilIdle()

            assertThat(vm.isAccountExpired.value).isTrue()
            assertThat(vm.titles).containsExactly("Anonymous")
        }

    @Test
    fun `a station of the home seeds its radio from this tab's provider`() {
        provider.id = "nl.neerdael.spotify"
        val vm = viewModel()

        val seed = ProviderEntityReference.decode(checkNotNull(vm.radioSeed("station-1")))

        assertThat(seed?.pluginId).isEqualTo("nl.neerdael.spotify")
        assertThat(seed?.entity).isEqualTo(EntityRef(EntityKind.PLAYLIST, "station-1"))
        assertThat(vm.radioSeed(null)).isNull()
        assertThat(MusicHomeFeedViewModel(null, CatalogPlayback { null }).radioSeed("station-1")).isNull()
        provider.id = LocalCatalogProvider.ID
        assertThat(viewModel().radioSeed("local-playlist")).isNull()
    }

    @Test
    fun `a reinstalled provider whose account stays anonymous loads its home again`() =
        runTest(dispatcher) {
            var fetches = 0
            provider.pages = {
                fetches++
                Result.success(page("Home $fetches"))
            }
            val installation = MutableStateFlow<Any?>("install-1")
            val vm = MusicHomeFeedViewModel(provider, CatalogPlayback { null }, installation)
            val shown = show(vm)
            vm.load()
            advanceUntilIdle()
            shown.cancelAndJoin()

            installation.value = null
            advanceUntilIdle()
            installation.value = "install-2"
            advanceUntilIdle()
            show(vm)
            runCurrent()
            vm.load()
            advanceUntilIdle()

            assertThat(fetches).isEqualTo(2)
            assertThat(vm.titles).containsExactly("Home 2")
        }

    @Test
    fun `a hidden tab stops following its account and installation`() =
        runTest(dispatcher) {
            provider.pages = { Result.success(page("Home")) }
            val installation = MutableStateFlow<Any?>("install-1")
            val vm = MusicHomeFeedViewModel(provider, CatalogPlayback { null }, installation)
            val shown = show(vm)
            vm.load()
            advanceUntilIdle()
            assertThat(installation.subscriptionCount.value).isEqualTo(1)

            shown.cancelAndJoin()
            advanceUntilIdle()

            assertThat(installation.subscriptionCount.value).isEqualTo(0)
        }

    @Test
    fun `the tab without a provider asks for one and fetches nothing`() =
        runTest(dispatcher) {
            var fetches = 0
            provider.pages = {
                fetches++
                Result.success(page("Home"))
            }
            val vm = MusicHomeFeedViewModel(null, CatalogPlayback { null })
            vm.load()
            advanceUntilIdle()

            assertThat(vm.state.value.needsPlugin).isTrue()
            assertThat(vm.state.value.isLoading).isFalse()
            assertThat(fetches).isEqualTo(0)
        }

    @Test
    fun `an empty local library asks for a scan and fills in when the index changes`() =
        runTest(dispatcher) {
            var indexed = false
            provider.pages =
                { if (indexed) Result.success(page("Recently added")) else Result.failure(LocalLibraryEmptyException("Indexing")) }
            val vm = viewModel()
            show(vm)
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
    fun `a transient first-page failure stays on screen and is fetched again on its own`() =
        runTest(dispatcher) {
            var calls = 0
            provider.pages = {
                if (calls++ == 0) Result.failure(pluginFailure(PluginErrorCode.NETWORK)) else Result.success(page("Recovered"))
            }
            val vm = viewModel()
            show(vm)

            vm.load()
            runCurrent()
            assertThat(vm.state.value.error).isEqualTo("NETWORK Spotify returned HTTP 500")
            assertThat(vm.state.value.isLoading).isFalse()

            advanceTimeBy(TransientRetryBackoffMs.first() - 1)
            assertThat(provider.requests).hasSize(1)

            advanceUntilIdle()
            assertThat(provider.requests).hasSize(2)
            assertThat(vm.titles).containsExactly("Recovered")
            assertThat(vm.state.value.error).isNull()
        }

    @Test
    fun `the automatic retries end with the backoff schedule and honour the provider's delay`() =
        runTest(dispatcher) {
            provider.pages = { Result.failure(pluginFailure(PluginErrorCode.RATE_LIMITED, retryAfterMs = 60_000)) }
            val vm = viewModel()
            show(vm)

            vm.load()
            advanceUntilIdle()

            assertThat(provider.requests).hasSize(TransientRetryBackoffMs.size + 1)
            assertThat(testScheduler.currentTime).isEqualTo(TransientRetryBackoffMs.sumOf { maxOf(it, 60_000) })
            assertThat(vm.state.value.error).isNotNull()
            assertThat(vm.state.value.isLoading).isFalse()
        }

    @Test
    fun `a lasting failure is not fetched again on its own`() =
        runTest(dispatcher) {
            for (code in listOf(PluginErrorCode.SIGN_IN_EXPIRED, PluginErrorCode.UNAVAILABLE, PluginErrorCode.INTERNAL)) {
                provider.requests.clear()
                provider.pages = { Result.failure(pluginFailure(code)) }
                val vm = viewModel()
                show(vm)

                vm.load()
                advanceUntilIdle()

                assertThat(provider.requests).hasSize(1)
                assertThat(vm.state.value.error).isNotNull()
            }
        }

    @Test
    fun `a retry the listener asks for replaces the pending one and keeps the failure shown until it ends`() =
        runTest(dispatcher) {
            val retried = CompletableDeferred<Result<MetadataPage>>()
            var calls = 0
            provider.pages = { if (calls++ == 0) Result.failure(pluginFailure(PluginErrorCode.TIMEOUT)) else retried.await() }
            val vm = viewModel()
            show(vm)
            vm.load()
            runCurrent()

            vm.load(force = true)
            runCurrent()
            assertThat(vm.state.value.error).isNotNull()
            assertThat(vm.state.value.isLoading).isTrue()

            retried.complete(Result.success(page("Recovered")))
            advanceUntilIdle()
            assertThat(provider.requests).hasSize(2)
            assertThat(vm.titles).containsExactly("Recovered")
            assertThat(vm.state.value.error).isNull()
        }

    @Test
    fun `a failed refresh keeps the shown blocks and is not fetched again on its own`() =
        runTest(dispatcher) {
            var calls = 0
            provider.pages = {
                if (calls++ == 0) Result.success(page("Before")) else Result.failure(pluginFailure(PluginErrorCode.NETWORK))
            }
            val vm = viewModel()
            show(vm)
            vm.load()
            advanceUntilIdle()

            vm.load(force = true)
            advanceUntilIdle()

            assertThat(provider.requests).hasSize(2)
            assertThat(vm.titles).containsExactly("Before")
        }

    @Test
    fun `a pending retry waits while the home is not shown and runs when the listener comes back`() =
        runTest(dispatcher) {
            var calls = 0
            provider.pages = {
                if (calls++ == 0) Result.failure(pluginFailure(PluginErrorCode.NETWORK)) else Result.success(page("Recovered"))
            }
            val vm = viewModel()
            val shown = show(vm)
            vm.load()
            runCurrent()

            shown.cancelAndJoin()
            advanceTimeBy(10 * 60_000L)
            assertThat(provider.requests).hasSize(1)
            assertThat(vm.state.value.isLoading).isFalse()
            assertThat(vm.state.value.error).isNotNull()

            show(vm)
            vm.load()
            runCurrent()
            advanceUntilIdle()
            assertThat(provider.requests).hasSize(2)
            assertThat(vm.titles).containsExactly("Recovered")
        }

    @Test
    fun `a manual reload replaces an automatic retry waiting for the home to return`() =
        runTest(dispatcher) {
            var calls = 0
            provider.pages = {
                if (calls++ == 0) Result.failure(pluginFailure(PluginErrorCode.NETWORK)) else Result.success(page("Reloaded"))
            }
            val vm = viewModel()
            val shown = show(vm)
            vm.load()
            runCurrent()

            shown.cancelAndJoin()
            advanceTimeBy(10 * 60_000L)
            vm.load(force = true)
            advanceUntilIdle()
            assertThat(vm.titles).containsExactly("Reloaded")

            show(vm)
            vm.load()
            runCurrent()
            advanceUntilIdle()
            assertThat(provider.requests).hasSize(2)
            assertThat(vm.titles).containsExactly("Reloaded")
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
