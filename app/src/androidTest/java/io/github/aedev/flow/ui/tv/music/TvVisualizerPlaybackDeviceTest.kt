package io.github.aedev.flow.ui.tv.music

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.audio.visualizer.VisualizerAudioTap
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import nl.neerdael.projectm.core.ProjectMJNI
import nl.neerdael.projectm.core.VisualizerView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import javax.inject.Inject

@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TvVisualizerPlaybackDeviceTest {
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Inject lateinit var engine: VisualizerEngine

    @Inject lateinit var tap: VisualizerAudioTap

    @Inject lateinit var preferences: VisualizerPreferences

    @Test
    fun visibleProjectMRequestsScreenOnDuringPlaybackAndReleasesItOnPauseAndExit() {
        assumeTrue(
            "Requires a temporary 15-second TV idle timeout",
            InstrumentationRegistry.getArguments().getString("visualizer_idle_test") == "true",
        )
        hilt.inject()
        assumeTrue(engine.isSupported)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val audio = File(instrumentation.targetContext.cacheDir, "visualizer-screen-test.m4a")
        instrumentation.context.assets
            .open("folders/tagged.m4a")
            .use { input -> audio.outputStream().use(input::copyTo) }
        val playerField = EnhancedMusicPlayerManager.javaClass.getDeclaredField("player").apply { isAccessible = true }
        val previousPlayer = EnhancedMusicPlayerManager.player
        lateinit var player: ExoPlayer
        val shown = mutableStateOf(true)
        compose.runOnUiThread {
            player = ExoPlayer.Builder(compose.activity).build()
            player.volume = 0f
            player.repeatMode = Player.REPEAT_MODE_ONE
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(audio)))
            playerField.set(null, player)
            player.prepare()
            player.play()
        }
        try {
            val viewModel = TvVisualizerViewModel(engine, tap, preferences)
            compose.setContent { if (shown.value) TvVisualizerBackground(viewModel) }
            var surface: VisualizerView? = null

            fun findSurface(): VisualizerView? {
                fun find(view: View): VisualizerView? {
                    if (view is VisualizerView) return view
                    if (view is ViewGroup) {
                        for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
                    }
                    return null
                }
                var result: VisualizerView? = null
                compose.runOnIdle { result = find(compose.activity.window.decorView) }
                return result
            }
            compose.waitUntil(60_000) {
                surface = findSurface()
                surface?.keepScreenOn == true && engine.renderStats != null
            }

            fun dreamRunning(): Boolean =
                ParcelFileDescriptor
                    .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys dreams"))
                    .bufferedReader()
                    .use { it.readText() }
                    .lineSequence()
                    .any { it.trim().startsWith("mCurrentDreamName=") && !it.trim().endsWith("=null") }

            // The acceptance session temporarily sets the device's idle timeout to 15 seconds.
            val firstFrame = ProjectMJNI.getRenderedFrameSerial()
            SystemClock.sleep(25_000)
            assertFalse("Ambient Mode interrupted the playing visualizer", dreamRunning())
            assertTrue("ProjectM stopped rendering during playback", ProjectMJNI.getRenderedFrameSerial() > firstFrame)
            compose.runOnIdle { player.pause() }
            compose.waitUntil(5_000) { surface?.keepScreenOn == false }
            val deadline = SystemClock.elapsedRealtime() + 60_000
            while (!dreamRunning() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(1_000)
            val power =
                ParcelFileDescriptor
                    .AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("dumpsys power"))
                    .bufferedReader()
                    .use { it.readText() }
            assertTrue("Ambient Mode did not start after the visualizer was paused. $power", dreamRunning())
            // Bring the test activity forward again without changing the user's saved timeout.
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            compose.runOnIdle { player.play() }
            compose.waitUntil(5_000) { surface?.keepScreenOn == true }
            compose.runOnIdle { shown.value = false }
            compose.waitForIdle()
            assertFalse(checkNotNull(surface).keepScreenOn)
        } finally {
            compose.runOnUiThread {
                shown.value = false
                playerField.set(null, previousPlayer)
                player.release()
            }
            audio.delete()
        }
    }
}
