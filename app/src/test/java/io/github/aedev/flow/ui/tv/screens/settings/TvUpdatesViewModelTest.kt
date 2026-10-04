package io.github.aedev.flow.ui.tv.screens.settings

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.update.AppRelease
import io.github.aedev.flow.data.update.AutoUpdater
import io.github.aedev.flow.plugin.install.PluginAutoUpdater
import io.github.aedev.flow.plugin.install.PluginUpdate
import io.github.aedev.flow.plugin.install.PluginUpdatesState
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvUpdatesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val release = AppRelease("0.9.8", "v0.9.8", "", null, "https://github.com/johnneerdael/Milkbeat/releases/tag/v0.9.8", null)
    private val ready = MutableStateFlow<AppRelease?>(null)
    private val pluginState = MutableStateFlow<PluginUpdatesState>(PluginUpdatesState.Idle)
    private val autoUpdater =
        mockk<AutoUpdater>(relaxed = true) {
            every { isAvailable } returns true
            every { enabled } returns flowOf(true)
            every { ready } returns this@TvUpdatesViewModelTest.ready
        }
    private val pluginUpdater =
        mockk<PluginAutoUpdater>(relaxed = true) {
            every { state } returns pluginState
            every { report } returns MutableStateFlow(null)
        }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = TvUpdatesViewModel(autoUpdater, pluginUpdater)

    @Test
    fun `a ready update is announced once while the app is open and again the next time it opens`() =
        runTest(dispatcher) {
            ready.value = release
            val session = viewModel()

            session.readyToInstall.test {
                assertThat(awaitItem()).isEqualTo("0.9.8")
                session.markAnnounced("0.9.8")
            }
            session.readyToInstall.test { expectNoEvents() }

            viewModel().readyToInstall.test { assertThat(awaitItem()).isEqualTo("0.9.8") }
        }

    @Test
    fun `settings needs attention while an update is ready or a plugin update waits for review`() =
        runTest(dispatcher) {
            val session = viewModel()

            session.needsAttention.test {
                assertThat(awaitItem()).isFalse()

                ready.value = release
                assertThat(awaitItem()).isTrue()

                ready.value = null
                assertThat(awaitItem()).isFalse()

                val waiting = PluginUpdate("yt", "YouTube Music", "0.5", 5, "https://buzzheavier.com/yt", "sha")
                pluginState.value = PluginUpdatesState.Checked(listOf(waiting), setOf("yt"))
                assertThat(awaitItem()).isTrue()

                pluginState.value = PluginUpdatesState.Checked(emptyList(), setOf("yt"), installed = listOf(waiting))
                assertThat(awaitItem()).isFalse()
            }
        }
}
