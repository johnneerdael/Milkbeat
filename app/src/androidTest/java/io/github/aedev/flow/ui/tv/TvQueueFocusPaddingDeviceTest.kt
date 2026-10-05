package io.github.aedev.flow.ui.tv

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.ui.tv.music.TvMusicQueuePanel
import io.github.aedev.flow.ui.tv.theme.TvTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TvQueueFocusPaddingDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val manager = EnhancedMusicPlayerManager

    @After fun cleanup() {
        manager.queueState.value = emptyList()
        manager.automixState.value = emptyList()
    }

    @Test
    fun focusedFirstRowFitsInsideQueueWithoutRadioPresets() {
        manager.queueState.value = (0..10).map { MusicTrack("$it", "Song $it", "Artist", "", 120) }
        manager.automixState.value = emptyList()
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Box(Modifier.width(650.dp).height(600.dp)) {
                    TvMusicQueuePanel(true, manager, {}, {})
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Song 0").assertIsFocused()
        val row = compose.onNodeWithText("Song 0").fetchSemanticsNode().boundsInRoot
        val list = compose.onNode(hasScrollAction() and hasAnyDescendant(hasText("Song 0"))).fetchSemanticsNode().boundsInRoot
        assertTrue("The scaled focus border needs space above the first row: row=$row list=$list", row.top > list.top)
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.cacheDir, "queue-focus-padding.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
