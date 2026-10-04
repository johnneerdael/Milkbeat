package io.github.aedev.flow.ui.tv.music

import android.app.Application
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.plugin.playback.RadioTuningState
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.catalog.FilterOption
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvQueuePresetFocusTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val manager = EnhancedMusicPlayerManager
    private val choices = listOf("All", "Familiar", "Popular", "Discover").map { FilterOption("opaque-$it", it) }

    @After fun cleanup() {
        manager.queueState.value = emptyList()
        manager.automixState.value = emptyList()
        manager.currentQueueIndexState.value = 0
    }

    @Test
    fun `right enters the selected preset and down from every preset restores the scrolled queue row`() {
        showPanel(queueSize = 100, currentIndex = 97, selectedId = "opaque-Familiar")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Song 98").assertIsFocused()
        val scrollBefore = scrollPosition()
        val rowBefore = compose.onNodeWithText("Song 98").fetchSemanticsNode().boundsInRoot

        choices.forEachIndexed { index, option ->
            press(KeyEvent.KEYCODE_DPAD_RIGHT)
            compose.onNodeWithText("Familiar").assertIsFocused()
            repeat(kotlin.math.abs(index - 1)) {
                press(if (index < 1) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            compose.onNodeWithText(option.label).assertIsFocused().assertIsDisplayed()
            assertThat(scrollPosition()).isEqualTo(scrollBefore)
            press(KeyEvent.KEYCODE_DPAD_DOWN)
            compose.onNodeWithText("Song 98").assertIsFocused()
            assertThat(scrollPosition()).isEqualTo(scrollBefore)
            assertThat(compose.onNodeWithText("Song 98").fetchSemanticsNode().boundsInRoot).isEqualTo(rowBefore)
        }
    }

    @Test
    fun `right from a radio row enters the first preset when selection is missing and down restores that row`() {
        showPanel(queueSize = 1, currentIndex = 0, radioSize = 4, selectedId = "missing")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Radio 0").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Radio 1").assertIsFocused()
        val scrollBefore = scrollPosition()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("All").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Radio 1").assertIsFocused()
        assertThat(scrollPosition()).isEqualTo(scrollBefore)
    }

    @Test
    fun `removing the remembered row returns to an existing row and remembers the replacement`() {
        showPanel(queueSize = 100, currentIndex = 97, selectedId = "opaque-Familiar")
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Song 98").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Familiar").assertIsFocused()
        compose.runOnIdle {
            manager.queueState.value = manager.queueState.value.filterNot { it.videoId == "queue-98" }
        }
        compose.waitForIdle()

        press(KeyEvent.KEYCODE_DPAD_DOWN)
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single()
        val replacementTitle = focused.config[SemanticsProperties.Text].first().text
        assertThat(manager.queue.value.map { it.title }).contains(replacementTitle)
        val scrollBefore = scrollPosition()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Familiar").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText(replacementTitle).assertIsFocused()
        assertThat(scrollPosition()).isEqualTo(scrollBefore)
    }

    @Test
    fun `appending radio tracks while presets have focus preserves the remembered radio row`() {
        showPanel(queueSize = 1, currentIndex = 0, radioSize = 4, selectedId = "opaque-Familiar")
        repeat(2) { press(KeyEvent.KEYCODE_DPAD_DOWN) }
        compose.onNodeWithText("Radio 1").assertIsFocused()
        val scrollBefore = scrollPosition()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Familiar").assertIsFocused()
        compose.runOnIdle {
            manager.automixState.value += MusicTrack("radio-new", "New radio", "Artist", "", 120)
        }
        compose.waitForIdle()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Radio 1").assertIsFocused()
        assertThat(scrollPosition()).isEqualTo(scrollBefore)
    }

    @Test
    fun `all of YouTube's presets sit on one scrolling line and right reaches the selected one at its far end`() {
        val labels =
            listOf(
                "All",
                "Popular",
                "Discover",
                "Deep cuts",
                "Party",
                "2010s",
                "Pump-up",
                "Workout",
                "2000s",
                "Electronic",
                "Focus",
                "Instrumental",
            )
        val many = labels.map { FilterOption("opaque-$it", it) }
        showPanel(queueSize = 100, currentIndex = 97, selectedId = "opaque-Instrumental", presets = many)
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Instrumental").assertIsFocused().assertIsDisplayed()
        repeat(labels.size - 1) { press(KeyEvent.KEYCODE_DPAD_LEFT) }
        compose.onNodeWithText("All").assertIsFocused().assertIsDisplayed()
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Song 98").assertIsFocused()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Instrumental").assertIsFocused().assertIsDisplayed()
    }

    @Test
    fun `right without presets keeps focus on the queue row`() {
        showPanel(queueSize = 100, currentIndex = 97, presets = emptyList())
        press(KeyEvent.KEYCODE_DPAD_DOWN)
        compose.onNodeWithText("Song 98").assertIsFocused()
        val scrollBefore = scrollPosition()
        press(KeyEvent.KEYCODE_DPAD_RIGHT)
        compose.onNodeWithText("Song 98").assertIsFocused()
        assertThat(scrollPosition()).isEqualTo(scrollBefore)
    }

    private fun showPanel(
        queueSize: Int,
        currentIndex: Int,
        radioSize: Int = 0,
        selectedId: String? = null,
        presets: List<FilterOption> = choices,
    ) {
        manager.queueState.value = List(queueSize) { MusicTrack("queue-$it", "Song $it", "Artist", "", 120) }
        manager.automixState.value = List(radioSize) { MusicTrack("radio-$it", "Radio $it", "Artist", "", 120) }
        manager.currentQueueIndexState.value = currentIndex
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Box(Modifier.width(650.dp).height(600.dp)) {
                    TvMusicQueuePanel(true, manager, {}, {}, RadioTuningState(presets, selectedId))
                }
            }
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.onNodeWithText("Song $currentIndex").assertIsFocused()
    }

    private fun scrollPosition(): Float =
        compose
            .onNode(hasScrollAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange]
            .value()

    private fun press(keyCode: Int) {
        val time = SystemClock.uptimeMillis()
        listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP).forEach { action ->
            compose.runOnIdle {
                compose.activity.dispatchKeyEvent(
                    KeyEvent(time, time, action, keyCode, 0, 0, -1, 0, 0, InputDevice.SOURCE_DPAD),
                )
            }
            compose.waitForIdle()
        }
    }
}
