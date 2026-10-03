package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.player.audio.visualizer.VisualizerAudioTap
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import nl.neerdael.projectm.core.ProjectMJNI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TvVisualizerCoreDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Inject lateinit var engine: VisualizerEngine

    @Inject lateinit var tap: VisualizerAudioTap

    @Inject lateinit var preferences: VisualizerPreferences

    @Test fun upstreamCoreRendersChangesPresetsAndStopsWhilePaused() {
        hilt.inject()
        assumeTrue(engine.isSupported)
        val viewModel = TvVisualizerViewModel(engine, tap, preferences)
        lateinit var host: TvVisualizerHost
        compose.setContent {
            AndroidView(factory = { context ->
                host = TvVisualizerHost(context, viewModel, engine.defaults)
                host.resume()
                host.view
            }, modifier = Modifier.fillMaxSize())
        }
        try {
            compose.waitUntil(60_000) { engine.renderStats != null && ProjectMJNI.getCurrentPresetName().isNotEmpty() }
            val stats = engine.renderStats!!
            assertEquals(30, engine.profile.defaultFrameRateCap())
            assertTrue(stats.autoResolution)
            assertTrue(stats.width > 0 && stats.height > 0)
            val before = ProjectMJNI.getPresetChangeCounter()
            viewModel.stepPreset(true)
            compose.waitUntil(30_000) { ProjectMJNI.getPresetChangeCounter() > before }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val screenshot = File(instrumentation.targetContext.externalCacheDir, "projectm-upstream.png")
            assertTrue(UiDevice.getInstance(instrumentation).takeScreenshot(screenshot))
            compose.runOnIdle { host.pause() }
            Thread.sleep(100)
            val paused = engine.renderStats
            Thread.sleep(1_500)
            assertSame(paused, engine.renderStats)
            compose.runOnIdle { host.resume() }
            compose.waitUntil(15_000) { engine.renderStats !== paused }
        } finally {
            compose.runOnIdle { host.close() }
        }
    }
}
