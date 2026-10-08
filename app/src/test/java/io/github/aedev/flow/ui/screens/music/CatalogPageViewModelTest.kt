package io.github.aedev.flow.ui.screens.music

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.plugin.catalog.toMusicTrack
import io.github.aedev.flow.plugin.mirror.MirrorKey
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorCoordinator
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorState
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogPageViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val playlist = EntityRef(EntityKind.PLAYLIST, "PL1")
    private val requests = mutableListOf<String?>()
    private val stores = mutableListOf<ViewModelStore>()
    private val collectors = mutableListOf<Job>()

    private fun tracks(vararg ids: String) =
        CollectionBlock(
            id = "tracks",
            header = null,
            layout = CollectionLayout.TRACK_TABLE,
            defaultItemView = ItemView.TRACK_ROW,
            items = ids.map { MetadataItem(id = "tracks#$it", entity = EntityRef(EntityKind.TRACK, it), title = it) },
        )

    private val header = EntityHeader(id = "header", style = HeaderStyle.COVER, entity = playlist, title = "Long playlist")

    private fun viewModel(
        accountState: MutableStateFlow<ProviderAccount> = MutableStateFlow(ProviderAccount.Anonymous),
        mirrors: PlaylistMirrorCoordinator? = null,
        playback: CatalogPlayback = CatalogPlayback { null },
        installation: Flow<Any?> = flowOf(null),
        pages: suspend (String?) -> Result<MetadataPage>,
    ) = CatalogPageViewModel(
        SavedStateHandle(
            mapOf(CatalogPageViewModel.KIND_ARG to playlist.kind.name, CatalogPageViewModel.ID_ARG to playlist.providerId),
        ),
        object : MetadataProvider {
            override val id = "fake"
            override val account = accountState

            override suspend fun home(request: HomeRequest): Result<MetadataPage> = Result.failure(UnsupportedOperationException())

            override suspend fun page(
                entity: EntityRef,
                cursor: String?,
            ): Result<MetadataPage> {
                requests += cursor
                return pages(cursor)
            }
        },
        playback,
        mockk<SubscriptionRepository> { every { isSubscribed(any()) } returns flowOf(false) },
        mirrors = mirrors,
        installation = installation,
    ).also { vm ->
        stores += ViewModelStore().apply { put("catalog", vm) }
        collectors += CoroutineScope(dispatcher).launch { vm.state.collect {} }
    }

    @Test
    fun `an updated plugin is another page identity, though the account stays the same`() =
        runTest(dispatcher) {
            val installation = MutableStateFlow<Any?>("install-1")
            val vm = viewModel(installation = installation) { Result.success(MetadataPage("p", listOf(header))) }
            val before = vm.sourceIdentity.first()
            vm.load(before)
            advanceUntilIdle()

            installation.value = "install-2"
            val after = vm.sourceIdentity.first()
            vm.load(after)
            advanceUntilIdle()

            assertThat(after).isNotEqualTo(before)
            assertThat(requests).hasSize(2)
        }

    @Test
    fun `a route without its provider shows an error instead of another provider's page`() =
        runTest(dispatcher) {
            val vm =
                CatalogPageViewModel(
                    SavedStateHandle(
                        mapOf(CatalogPageViewModel.KIND_ARG to playlist.kind.name, CatalogPageViewModel.ID_ARG to playlist.providerId),
                    ),
                    subscriptions = mockk { every { isSubscribed(any()) } returns flowOf(false) },
                )
            stores += ViewModelStore().apply { put("catalog", vm) }
            collectors += CoroutineScope(dispatcher).launch { vm.state.collect {} }

            vm.load()
            advanceUntilIdle()

            assertThat(vm.state.value.blocks).isEmpty()
            assertThat(vm.state.value.isLoading).isFalse()
            assertThat(vm.state.value.error).isNotNull()
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        stores.forEach(ViewModelStore::clear)
        collectors.forEach(Job::cancel)
        Dispatchers.resetMain()
    }

    @Test
    fun `default provider routes carry provider identity into playlist playback`() {
        val vm = viewModel { Result.success(MetadataPage("playlist", emptyList())) }
        val seed = checkNotNull(vm.radioSeed(playlist.providerId))
        val scoped =
            io.github.aedev.flow.plugin.catalog.ProviderEntityReference
                .decode(seed)
        assertThat(scoped?.pluginId).isEqualTo("fake")
        assertThat(scoped?.entity).isEqualTo(playlist)
    }

    @Test
    fun `a long playlist's further pages extend its tracks, in order`() =
        runTest(dispatcher) {
            val vm =
                viewModel { cursor ->
                    Result.success(
                        when (cursor) {
                            null -> MetadataPage("p", listOf(header, tracks("a", "b")), nextCursor = "c1")
                            "c1" -> MetadataPage("tracks", listOf(tracks("c", "d")), nextCursor = "c2")
                            else -> MetadataPage("tracks", listOf(tracks("e")))
                        },
                    )
                }

            vm.load()
            advanceUntilIdle()

            val blocks = vm.state.value.blocks
            assertThat(blocks.map { it.id }).containsExactly("header", "tracks").inOrder()
            assertThat((blocks[1] as CollectionBlock).items.map { it.entity.providerId }).containsExactly("a", "b", "c", "d", "e").inOrder()
            assertThat(requests).containsExactly(null, "c1", "c2").inOrder()
        }

    @Test
    fun `a failed page is shown as an error and loads again on the next visit`() =
        runTest(dispatcher) {
            var online = false
            val vm =
                viewModel {
                    if (online) Result.success(MetadataPage("p", listOf(header))) else Result.failure(IllegalStateException("offline"))
                }

            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.error).isEqualTo("offline")

            online = true
            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.blocks).containsExactly(header)
            assertThat(vm.state.value.error).isNull()
        }

    @Test
    fun `a loaded page is not fetched again`() =
        runTest(dispatcher) {
            val vm = viewModel { Result.success(MetadataPage("p", listOf(header))) }

            vm.load()
            advanceUntilIdle()
            vm.load()
            advanceUntilIdle()

            assertThat(requests).hasSize(1)
        }

    @Test fun aChangedAccountCannotReusePrivatePlaylistBlocks() =
        runTest(dispatcher) {
            val account = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("a"))
            val vm =
                viewModel(account) {
                    val owner = (account.value as ProviderAccount.SignedIn).key
                    Result.success(MetadataPage("p", listOf(tracks(owner))))
                }
            vm.load()
            advanceUntilIdle()
            account.value = ProviderAccount.SignedIn("b")
            vm.load()
            advanceUntilIdle()
            assertThat(
                (
                    vm.state.value.blocks
                        .single() as CollectionBlock
                ).items.single().title,
            ).isEqualTo("b")
        }

    @Test fun signingOutHidesCachedPrivatePlaylistWithoutAnotherFetch() =
        runTest(dispatcher) {
            val account = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("a"))
            val vm = viewModel(account) { Result.success(MetadataPage("p", listOf(header))) }
            vm.load()
            advanceUntilIdle()
            assertThat(vm.state.value.blocks).isNotEmpty()
            account.value = ProviderAccount.Anonymous
            advanceUntilIdle()
            assertThat(vm.state.value.blocks).isEmpty()
            assertThat(requests).containsExactly(null)
        }

    @Test fun retiredAccountResultsCannotOverwriteNewAccount() =
        runTest(dispatcher) {
            val account = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("a"))
            val oldStarted = CompletableDeferred<Unit>()
            val finishOld = CompletableDeferred<Unit>()
            val vm =
                viewModel(account) {
                    val owner = (account.value as ProviderAccount.SignedIn).key
                    if (owner == "a") {
                        oldStarted.complete(Unit)
                        withContext(NonCancellable) { finishOld.await() }
                    }
                    Result.success(MetadataPage("p", listOf(tracks(owner))))
                }
            vm.load()
            runCurrent()
            assertThat(oldStarted.isCompleted).isTrue()
            account.value = ProviderAccount.SignedIn("b")
            vm.load()
            finishOld.complete(Unit)
            advanceUntilIdle()
            assertThat(
                (
                    vm.state.value.blocks
                        .single() as CollectionBlock
                ).items.single().title,
            ).isEqualTo("b")
        }

    private val mirrorKey = MirrorKey("fake", "a", "target", "b", playlist)

    private fun coordinator(
        selected: MutableStateFlow<MirrorKey?>,
        states: Map<MirrorKey, MutableStateFlow<PlaylistMirrorState>>,
    ) = mockk<PlaylistMirrorCoordinator>(relaxed = true) {
        every { observeSelectedKey(any(), any()) } returns selected
        every { state(any()) } answers { checkNotNull(states[firstArg()]) }
    }

    @Test
    fun `ready mirror status clears when the selected pair becomes unavailable`() =
        runTest(dispatcher) {
            val selected = MutableStateFlow<MirrorKey?>(mirrorKey)
            val progress = MutableStateFlow(PlaylistMirrorState(ready = true, matched = 3))
            val mirrors = coordinator(selected, mapOf(mirrorKey to progress))
            val vm = viewModel(mirrors = mirrors) { Result.success(MetadataPage("p", listOf(header, tracks("a")))) }
            vm.load()
            runCurrent()
            assertThat(vm.mirror.value.ready).isTrue()

            selected.value = null
            runCurrent()
            assertThat(vm.mirror.value).isEqualTo(PlaylistMirrorState())
            assertThat(
                vm.state.value.blocks
                    .first(),
            ).isEqualTo(header)
            assertThat((vm.state.value.blocks[1] as CollectionBlock).items.map { it.entity.providerId }).containsExactly("a")
            assertThat(requests).containsExactly(null)
            progress.value = PlaylistMirrorState(error = "late failure")
            runCurrent()
            assertThat(vm.mirror.value).isEqualTo(PlaylistMirrorState())
            verify(exactly = 1) { mirrors.open(any(), any(), any(), any()) }
        }

    @Test
    fun `failed mirror status clears when a target becomes unavailable`() =
        runTest(dispatcher) {
            val selected = MutableStateFlow<MirrorKey?>(mirrorKey)
            val mirrors = coordinator(selected, mapOf(mirrorKey to MutableStateFlow(PlaylistMirrorState(error = "offline"))))
            val vm = viewModel(mirrors = mirrors) { Result.success(MetadataPage("p", listOf(header))) }
            vm.load()
            runCurrent()
            assertThat(vm.mirror.value.error).isEqualTo("offline")

            selected.value = null
            runCurrent()
            assertThat(vm.mirror.value).isEqualTo(PlaylistMirrorState())
            assertThat(vm.state.value.blocks).containsExactly(header)
            assertThat(requests).containsExactly(null)
        }

    @Test
    fun `account changes clear mirror status before a replacement page is loaded`() =
        runTest(dispatcher) {
            val account = MutableStateFlow<ProviderAccount>(ProviderAccount.SignedIn("a"))
            val selected = MutableStateFlow<MirrorKey?>(mirrorKey)
            val progress = MutableStateFlow(PlaylistMirrorState(ready = true))
            val mirrors = coordinator(selected, mapOf(mirrorKey to progress))
            val vm = viewModel(account, mirrors) { Result.success(MetadataPage("p", listOf(header))) }
            vm.load()
            runCurrent()
            assertThat(vm.mirror.value.ready).isTrue()

            account.value = ProviderAccount.SignedIn("other")
            runCurrent()
            assertThat(vm.mirror.value).isEqualTo(PlaylistMirrorState())
            assertThat(vm.state.value.blocks).isEmpty()
            assertThat(requests).containsExactly(null)
            progress.value = PlaylistMirrorState(error = "retired account")
            runCurrent()
            assertThat(vm.mirror.value).isEqualTo(PlaylistMirrorState())
            verify(exactly = 1) { mirrors.open(any(), any(), any(), any()) }
        }

    @Test
    fun `a replaced mirror collector cannot overwrite the current selection`() =
        runTest(dispatcher) {
            val replacement = mirrorKey.copy(targetPlugin = "replacement")
            val selected = MutableStateFlow<MirrorKey?>(mirrorKey)
            val retired = MutableStateFlow(PlaylistMirrorState(ready = true))
            val current = MutableStateFlow(PlaylistMirrorState(isPreparing = true, total = 3))
            val mirrors = coordinator(selected, mapOf(mirrorKey to retired, replacement to current))
            val vm = viewModel(mirrors = mirrors) { Result.success(MetadataPage("p", listOf(header))) }
            vm.load()
            runCurrent()
            assertThat(vm.mirror.value.ready).isTrue()

            selected.value = replacement
            runCurrent()
            assertThat(vm.mirror.value).isEqualTo(current.value)
            retired.value = PlaylistMirrorState(error = "retired target")
            runCurrent()
            assertThat(vm.mirror.value).isEqualTo(current.value)
            current.value = PlaylistMirrorState(ready = true, matched = 3)
            runCurrent()
            assertThat(vm.mirror.value).isEqualTo(current.value)
            assertThat(vm.state.value.blocks).containsExactly(header)
            assertThat(requests).containsExactly(null)
            verify(exactly = 2) { mirrors.open(any(), any(), any(), any()) }
        }

    @Test
    fun `repeated tracks retain their row positions when the same item id is reused`() =
        runTest(dispatcher) {
            val table = tracks("a", "b", "a")
            val vm =
                viewModel(playback = CatalogPlayback { TrackDescriptor(it.entity, it.title).toMusicTrack("fake") }) {
                    Result.success(MetadataPage("p", listOf(header, table)))
                }
            vm.load()
            runCurrent()
            val rows = (vm.state.value.blocks[1] as CollectionBlock).items

            assertThat(rows.map { vm.track(it)?.sourcePosition }).containsExactly(0, 1, 2).inOrder()
            assertThat(rows.map { vm.track(it)?.videoId }).containsExactly("a", "b", "a").inOrder()
        }

    @Test
    fun `repeated track occurrences on continuation pages remain selectable at their own positions`() =
        runTest(dispatcher) {
            val vm =
                viewModel(playback = CatalogPlayback { TrackDescriptor(it.entity, it.title).toMusicTrack("fake") }) { cursor ->
                    Result.success(
                        if (cursor == null) {
                            MetadataPage("p", listOf(header, tracks("a", "b")), nextCursor = "next")
                        } else {
                            MetadataPage("next", listOf(tracks("a", "c", "a")))
                        },
                    )
                }
            vm.load()
            runCurrent()
            val rows = (vm.state.value.blocks[1] as CollectionBlock).items

            assertThat(rows.map { it.entity.providerId }).containsExactly("a", "b", "a", "c", "a").inOrder()
            assertThat(rows.map { vm.track(it)?.sourcePosition }).containsExactly(0, 1, 2, 3, 4).inOrder()
            assertThat(requests).containsExactly(null, "next").inOrder()
        }
}
