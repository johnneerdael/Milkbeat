package io.github.aedev.flow.ui.tv.screens.music

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.catalog.MusicSources
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.data.folders.MusicFolderRepository
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class TvMusicTabsViewModelTest {
    @get:Rule val temp = TemporaryFolder()
    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.sources(): MusicSources {
        val registry = mockk<PluginRegistry> { every { state } returns MutableStateFlow(PluginRegistryState()) }
        val folders =
            mockk<MusicFolderRepository> {
                every { folders } returns flowOf(listOf(MusicFolder(name = "Music", kind = MusicFolderKind.LOCAL)))
            }
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { temp.newFile("music_tabs.preferences_pb") }
        return MusicSources(registry, PluginAccounts(mockk<PluginHost>(), backgroundScope) { 0L }, folders, store, backgroundScope)
    }

    @Test
    fun `coming back after the tabs stopped being watched keeps them ready`() =
        runTest(dispatcher) {
            val vm = TvMusicTabsViewModel(sources())
            val first = backgroundScope.launch { vm.state.collect {} }
            runCurrent()
            advanceUntilIdle()
            assertThat(vm.state.value.selected).isEqualTo(MusicSource.Local)
            first.cancelAndJoin()
            advanceTimeBy(10_000)

            val seen = mutableListOf<Boolean>()
            backgroundScope.launch { vm.state.collect { seen += it.ready } }
            runCurrent()
            advanceUntilIdle()

            assertThat(seen).doesNotContain(false)
        }
}
