package io.github.aedev.flow.ui.tv.music

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.screens.settings.TvVisualizerSettingsPane
import io.github.aedev.flow.ui.tv.screens.settings.TvVisualizerSettingsViewModel
import io.github.aedev.flow.ui.tv.theme.TvTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TvNativeTrailsSettingsDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Inject lateinit var engine: VisualizerEngine

    @Inject lateinit var preferences: VisualizerPreferences

    @Test
    fun automaticQualityAndTrailPickerPersistAndReturnRemoteFocus() {
        hilt.inject()
        assumeTrue(engine.isSupported)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        runBlocking {
            preferences.setEnabled(true)
            preferences.setNativeTrails(0)
        }
        val viewModel = TvVisualizerSettingsViewModel(context, preferences, engine)
        val store = ViewModelStore().apply { put("native-trails", viewModel) }
        compose.setContent {
            TvTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    TvScreenScaffold(title = null) { TvVisualizerSettingsPane(viewModel = viewModel) }
                }
            }
        }
        try {
            compose.waitUntil(15_000) { viewModel.settings.value != null }
            val trails = context.getString(R.string.visualizer_native_trails)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(trails))
            compose.onNodeWithText(context.getString(R.string.visualizer_resolution)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.visualizer_memory_limit)).assertDoesNotExist()
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "native-quality-settings.png")))
            device.pressDPadDown() // Exit touch mode on the owned phone emulator before remote input.
            val opener = compose.onNode(hasText(trails) and hasClickAction())
            opener.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            opener.performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitForIdle()
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "native-trails-picker.png")))
            val selected =
                compose.onNode(
                    hasText(context.getString(R.string.visualizer_native_trails_standard)) and hasClickAction() and !hasText(trails),
                )
            selected.assertIsFocused()
            selected.performKeyInput {
                pressKey(Key.DirectionDown)
                pressKey(Key.DirectionDown)
                pressKey(Key.DirectionCenter)
            }
            compose.waitUntil(15_000) { viewModel.settings.value?.clampedNativeTrails == 2 }
            compose.onNode(hasText(trails) and hasClickAction()).assertIsFocused()
            val stored = runBlocking { preferences.settings(engine.defaults).first() }
            assertTrue(stored.clampedNativeTrails == 2)
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }
}
