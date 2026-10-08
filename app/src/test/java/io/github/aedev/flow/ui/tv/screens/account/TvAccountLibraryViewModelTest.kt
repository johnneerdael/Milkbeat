package io.github.aedev.flow.ui.tv.screens.account

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.catalog.ScopedPluginCatalog
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginOperations
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvAccountLibraryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val account = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("first"))
    private val requests = mutableListOf<LibraryRequest>()
    private var sourceId = "first-provider"
    private val provider =
        mockk<ScopedPluginCatalog> {
            every { account } returns this@TvAccountLibraryViewModelTest.account
            every { id } answers { sourceId }
            coEvery { call(PluginOperations.library, any()) } answers {
                val request = secondArg<LibraryRequest>()
                requests += request
                Result.success(
                    when (request.cursor) {
                        null -> MetadataPage("liked", listOf(tracks("a", "b")), nextCursor = "more")
                        else -> MetadataPage("liked", listOf(tracks("c")))
                    },
                )
            }
        }

    private fun tracks(vararg ids: String) =
        CollectionBlock(
            id = "tracks",
            header = null,
            layout = CollectionLayout.TRACK_TABLE,
            defaultItemView = ItemView.TRACK_ROW,
            items = ids.map { MetadataItem(id = "tracks#$it", entity = EntityRef(EntityKind.TRACK, it), title = it) },
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
    fun `an updated plugin is another library identity, though the account stays the same`() =
        runTest(dispatcher) {
            val installation = MutableStateFlow<Any?>(1L to 1)
            val vm = TvAccountLibraryViewModel(provider, installation)
            val before = vm.accountIdentity.first()

            installation.value = 2L to 2

            assertThat(vm.accountIdentity.first()).isNotEqualTo(before)
        }

    @Test
    fun `a section asks the plugin for its library section and extends it with the pages that follow`() =
        runTest(dispatcher) {
            val vm = TvAccountLibraryViewModel(provider)

            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            advanceUntilIdle()

            val state = vm.sections.value.getValue(TvAccountLibrarySection.LIKED_MUSIC)
            assertThat(
                (state.blocks.single() as CollectionBlock).items.map { it.entity.providerId },
            ).containsExactly("a", "b", "c").inOrder()
            assertThat(requests).containsExactly(LibraryRequest("liked"), LibraryRequest("liked", "more")).inOrder()
        }

    @Test
    fun `reopening a section just read asks nothing, another account reads it again`() =
        runTest(dispatcher) {
            val vm = TvAccountLibraryViewModel(provider)
            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            advanceUntilIdle()

            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            advanceUntilIdle()
            assertThat(requests).hasSize(2)

            account.value = ProviderAccount.SignedIn("second")
            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            advanceUntilIdle()
            assertThat(requests).hasSize(4)
            assertThat(
                vm.sections.value
                    .getValue(TvAccountLibrarySection.LIKED_MUSIC)
                    .accountKey,
            ).isEqualTo("second")
        }

    @Test
    fun `a signed-out section shows the plugin's reason`() =
        runTest(dispatcher) {
            coEvery { provider.call(PluginOperations.library, any()) } returns Result.failure(IllegalStateException("Sign in first"))
            val vm = TvAccountLibraryViewModel(provider)

            vm.open(TvAccountLibrarySection.PLAYLISTS)
            advanceUntilIdle()

            val state = vm.sections.value.getValue(TvAccountLibrarySection.PLAYLISTS)
            assertThat(state.error).isEqualTo("Sign in first")
            assertThat(state.isLoading).isFalse()
        }

    @Test
    fun `the watch history is paged, not read ahead`() =
        runTest(dispatcher) {
            val vm = TvAccountLibraryViewModel(provider)

            vm.open(TvAccountLibrarySection.WATCH_HISTORY)
            advanceUntilIdle()

            assertThat(requests).isEmpty()
        }

    @Test
    fun `the initial section requests the provider overview`() =
        runTest(dispatcher) {
            val vm = TvAccountLibraryViewModel(provider)
            vm.open(TvAccountLibrarySection.entries.first())
            advanceUntilIdle()
            assertThat(requests).contains(LibraryRequest())
        }

    @Test
    fun `another provider with the same account key does not reuse the old section`() =
        runTest(dispatcher) {
            val vm = TvAccountLibraryViewModel(provider)
            vm.open(TvAccountLibrarySection.PLAYLISTS)
            advanceUntilIdle()
            sourceId = "second-provider"
            vm.open(TvAccountLibrarySection.PLAYLISTS)
            advanceUntilIdle()
            assertThat(requests).hasSize(4)
        }

    @Test
    fun `a discarded page after sign out does not block the same account from loading again`() =
        runTest(dispatcher) {
            val pending = CompletableDeferred<MetadataPage>()
            var calls = 0
            coEvery { provider.call(PluginOperations.library, any()) } coAnswers {
                calls++
                Result.success(if (calls == 1) pending.await() else MetadataPage("liked", listOf(tracks("fresh"))))
            }
            val vm = TvAccountLibraryViewModel(provider)
            vm.accountChanged("$sourceId:first")
            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            runCurrent()
            account.value = ProviderAccount.Anonymous
            pending.complete(MetadataPage("liked", listOf(tracks("old"))))
            advanceUntilIdle()
            assertThat(vm.sections.value[TvAccountLibrarySection.LIKED_MUSIC]).isNull()
            account.value = ProviderAccount.SignedIn("first")
            vm.accountChanged("$sourceId:first")
            vm.open(TvAccountLibrarySection.LIKED_MUSIC)
            advanceUntilIdle()
            assertThat(calls).isEqualTo(2)
            assertThat(
                vm.sections.value
                    .getValue(TvAccountLibrarySection.LIKED_MUSIC)
                    .isLoading,
            ).isFalse()
        }
}
