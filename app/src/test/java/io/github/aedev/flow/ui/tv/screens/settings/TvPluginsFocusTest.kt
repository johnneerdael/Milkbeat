package io.github.aedev.flow.ui.tv.screens.settings

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.ui.tv.screens.TvSettingsScreen
import io.github.aedev.flow.ui.tv.theme.TvTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import nl.neerdael.milkbeat.plugin.ApiRange
import nl.neerdael.milkbeat.plugin.AudioRole
import nl.neerdael.milkbeat.plugin.PluginManifest
import nl.neerdael.milkbeat.plugin.Roles
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvPluginsFocusTest {
    @get:Rule val compose = createComposeRule()
    private val state =
        MutableStateFlow(
            TvPluginsState(
                plugins = listOf(plugin("beatport", "Beatport"), plugin("youtube", "YouTube Music")),
                selection = ProviderSelection(audio = listOf("beatport", "youtube")),
            ),
        )
    private val viewModel = mockk<TvPluginsViewModel>(relaxed = true)

    private fun plugin(
        id: String,
        name: String,
    ) = InstalledPlugin(
        PluginManifest(1, ApiRange(1, 2), id, name, "1.0", 1, roles = Roles(audio = AudioRole(setOf(id)))),
        "signer",
        "https://example.test/$id",
        0,
        emptyList(),
        emptyList(),
    )

    private fun show() {
        every { viewModel.state } returns state
        every { viewModel.playHistoryEnabled } returns MutableStateFlow(true)
        every { viewModel.consumeHomeRequest(any()) } returns false
        every { viewModel.mutateSelection(any()) } answers {
            val change = firstArg<(ProviderSelection) -> ProviderSelection>()
            state.value = state.value.copy(selection = change(state.value.selection))
        }
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Row {
                    Button({}, Modifier.width(180.dp)) { Text("Left menu") }
                    TvPluginsSettingsPane({ _, _ -> }, Modifier.width(650.dp), viewModel = viewModel)
                }
            }
        }
    }

    private fun focus(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(text).assertIsFocused()
    }

    @Test
    fun `opening audio focuses its controls instead of the menu`() {
        show()
        focus("Audio")
        compose.onNodeWithText("Audio").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Move later").assertIsFocused()
    }

    @Test
    fun `pointer activation also gives focus to the new pane`() {
        show()
        compose.onNodeWithText("Audio").performTouchInput { click() }
        compose.waitForIdle()
        compose.onNodeWithText("Move later").assertIsFocused()
    }

    @Test
    fun `moving to the last position keeps focus on the moved provider`() {
        show()
        compose.onNodeWithText("Audio").performClick()
        focus("Move later")
        compose.onNodeWithText("Move later").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Move earlier").assertIsFocused()
    }

    @Test
    fun `moving to the first position keeps focus on the moved provider`() {
        state.value = state.value.copy(selection = ProviderSelection(audio = listOf("youtube", "beatport")))
        show()
        compose.onNodeWithText("Audio").performClick()
        focus("Move earlier")
        compose.onNodeWithText("Move earlier").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Move later").assertIsFocused()
    }

    @Test
    fun `activating a settings category moves focus into its pane`() {
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            TvTheme { TvSettingsScreen(initialCategory = TvSettingsCategory.PLAYBACK) }
        }
        focus("Playback")
        val categoryRight =
            compose
                .onNodeWithText("Playback")
                .fetchSemanticsNode()
                .boundsInRoot.right
        compose.onNodeWithText("Playback").performClick()
        compose.waitForIdle()
        val focused = compose.onAllNodes(isFocused()).fetchSemanticsNodes().single()
        assertThat(focused.boundsInRoot.left).isGreaterThan(categoryRight)
    }

    @Test
    fun `opening plugin details focuses the detail action`() {
        show()
        focus("Beatport")
        compose.onNodeWithText("Beatport").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove").assertIsFocused()
    }
}
