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
import androidx.compose.ui.test.onLast
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
import kotlinx.coroutines.runBlocking
import nl.neerdael.projectm.core.NativeCrashReports
import nl.neerdael.projectm.core.ProjectMCore
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

/** Two opt-in runs on a disposable device: capture intentionally kills the app, then inspect it. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TvVisualizerCrashReportDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Inject lateinit var engine: VisualizerEngine

    @Inject lateinit var preferences: VisualizerPreferences

    @Test
    fun captureNativeCrashWhenExplicitlyRequested() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("projectmCrashCapture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ProjectMCore.init(context)
        assertTrue(NativeCrashReports.status().contains("ready", ignoreCase = true))
        android.os.Process.sendSignal(android.os.Process.myPid(), 11)
        error("The opt-in native crash did not terminate the process")
    }

    @Test
    fun savedNativeBacktraceIsReadableAndBackReturnsRemoteFocus() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("projectmCrashReport") == "true")
        hilt.inject()
        assumeTrue(engine.isSupported)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        ProjectMCore.init(context)
        val saved = NativeCrashReports.recent(context)
        assertTrue("Capture must run first", saved.isNotEmpty())
        assertTrue(saved.first().details.contains("Native backtrace"))
        runBlocking { preferences.setEnabled(true) }
        val viewModel = TvVisualizerSettingsViewModel(context, preferences, engine)
        val store = ViewModelStore().apply { put("crash-report", viewModel) }
        compose.setContent {
            TvTheme {
                Surface(color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
                    TvScreenScaffold(title = null) { TvVisualizerSettingsPane(viewModel = viewModel) }
                }
            }
        }
        try {
            compose.waitUntil(15_000) { viewModel.settings.value != null && viewModel.exitRecords.value != null }
            val lastExit = context.getString(R.string.visualizer_last_exit)
            compose.onNode(hasScrollAction()).performScrollToNode(hasText(lastExit))
            compose.onNodeWithText("Background compile").assertDoesNotExist()
            compose.onNodeWithText("Shader binary cache").assertDoesNotExist()
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "native-crash-settings.png")))
            device.pressDPadDown()
            val opener = compose.onNode(hasText(lastExit) and hasClickAction())
            opener.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            opener.performKeyInput { pressKey(Key.DirectionCenter) }
            compose.waitForIdle()
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "native-crash-report.png")))
            compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("Native backtrace", substring = true))
            val backtrace = compose.onNode(hasText("Native backtrace", substring = true) and hasClickAction())
            backtrace.performSemanticsAction(SemanticsActions.RequestFocus) { it() }
            backtrace.assertIsFocused()
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "native-crash-backtrace.png")))
            backtrace.performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
            compose.onAllNodes(hasScrollAction()).onLast().performScrollToNode(hasText("Build IDs", substring = true))
            assertTrue(device.takeScreenshot(File(context.externalCacheDir, "native-crash-build-ids.png")))
            device.pressBack()
            compose.waitForIdle()
            opener.assertIsFocused()
        } finally {
            compose.runOnIdle { store.clear() }
        }
    }
}
