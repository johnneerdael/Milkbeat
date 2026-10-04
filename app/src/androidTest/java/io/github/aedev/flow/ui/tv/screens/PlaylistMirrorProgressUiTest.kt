package io.github.aedev.flow.ui.tv.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.mirror.MirrorPhase
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorState
import io.github.aedev.flow.ui.tv.theme.TvTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PlaylistMirrorProgressUiTest {
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val originallyAwake = device.isScreenOn

    @get:Rule
    val compose = createComposeRule()

    @After
    fun restoreScreenState() {
        if (!originallyAwake) device.sleep()
    }

    @Test
    fun percentageLabelsRenderMatchingWritingAndVerificationBeforeReady() {
        device.wakeUp()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val state = mutableStateOf(PlaylistMirrorState(isPreparing = true))
        var retries = 0
        compose.setContent {
            TvTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    TvPlaylistMirrorStatus(state.value, null) { retries++ }
                }
            }
        }
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_starting, 0)).assertIsDisplayed()
        compose.runOnIdle {
            state.value = PlaylistMirrorState(total = 100, matched = 60, isPreparing = true, phase = MirrorPhase.MATCHING)
        }
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_progress_percent, 51, 60, 100, 0)).assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(phase = MirrorPhase.WRITING, phaseCompleted = 50, phaseTotal = 100)
        }
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_writing, 90, 0)).assertIsDisplayed()
        compose.runOnIdle {
            state.value = state.value.copy(phase = MirrorPhase.VERIFYING, phaseCompleted = 100, phaseTotal = 100)
        }
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_verifying, 99, 0)).assertIsDisplayed()
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(context.cacheDir, "playlist-mirror-progress.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.runOnIdle { state.value = state.value.copy(ready = true, isPreparing = false, matched = 100) }
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_ready, 100, 0)).assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(ready = false, error = "synthetic failure") }
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_failed)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.playlist_mirror_retry)).performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }
}
