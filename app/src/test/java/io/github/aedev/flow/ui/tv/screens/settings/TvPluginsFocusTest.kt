package io.github.aedev.flow.ui.tv.screens.settings

import android.app.Application
import androidx.activity.OnBackPressedDispatcher
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
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
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.plugin.install.PluginUpdate
import io.github.aedev.flow.plugin.install.PluginUpdatesState
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

    private var back: OnBackPressedDispatcher? = null
    private var leftSettings = 0
    private var openedAppUpdates = 0

    private fun pressBack() {
        compose.runOnIdle { back!!.onBackPressed() }
        compose.waitForIdle()
    }

    private fun show() {
        every { viewModel.state } returns state
        every { viewModel.playHistoryEnabled } returns MutableStateFlow(true)
        every { viewModel.automaticUpdates } returns MutableStateFlow(true)
        every { viewModel.consumeHomeRequest(any()) } returns false
        every { viewModel.mutateSelection(any()) } answers {
            val change = firstArg<(ProviderSelection) -> ProviderSelection>()
            state.value = state.value.copy(selection = change(state.value.selection))
        }
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            back = LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            // Stands in for the shell's Back, which leaves Settings: composed first, as the shell's is.
            BackHandler { leftSettings++ }
            TvTheme {
                Row {
                    Button({}, Modifier.width(180.dp)) { Text("Left menu") }
                    TvPluginsSettingsPane(
                        onSignIn = { _, _ -> },
                        onOpenAppUpdates = { openedAppUpdates++ },
                        modifier = Modifier.width(650.dp),
                        viewModel = viewModel,
                    )
                }
            }
        }
    }

    private fun focus(text: String) {
        compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText(text).assertIsFocused()
    }

    private fun showNeedingAppUpdate(appUpdates: Boolean) {
        val waiting = PluginUpdate("youtube", "YouTube Music", "2.0", 2, "https://example.test/new", "sha")
        state.value =
            state.value.copy(
                adding = AddPluginState.RequiresAppUpdate("Deezer"),
                updates = PluginUpdatesState.Checked(emptyList(), setOf("beatport", "youtube"), requiresAppUpdate = listOf(waiting)),
            )
        every { viewModel.appUpdatesAvailable } returns appUpdates
        show()
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
    }

    @Test
    fun `a plugin needing a newer Milkbeat says so and opens the app updater`() {
        showNeedingAppUpdate(appUpdates = true)
        scrollTo("1 update needs a newer version of Milkbeat")
        scrollTo("Update YouTube Music")
        compose.onNodeWithText("Update YouTube Music").performClick()
        scrollTo("Deezer needs a newer version of Milkbeat. Update Milkbeat, then install the plugin again.")
        scrollTo("Update Milkbeat")
        compose.onNodeWithText("Update Milkbeat").performClick()

        assertThat(openedAppUpdates).isEqualTo(2)
    }

    @Test
    fun `without the app updater a plugin needing a newer Milkbeat only says so`() {
        showNeedingAppUpdate(appUpdates = false)
        scrollTo("Deezer needs a newer version of Milkbeat. Update Milkbeat, then install the plugin again.")
        compose.onNodeWithText("Update Milkbeat").assertDoesNotExist()
        scrollTo("Update YouTube Music")
        compose.onNodeWithText("Update YouTube Music").performClick()

        assertThat(openedAppUpdates).isEqualTo(0)
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

    @Test
    fun `Back closes audio priority and plugin details instead of leaving Settings`() {
        show()
        compose.onNodeWithText("Audio").performClick()
        compose.waitForIdle()
        pressBack()
        compose.onNodeWithText("Move later").assertDoesNotExist()
        compose.onNodeWithText("Audio").assertExists()

        compose.onNodeWithText("Beatport").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Remove").assertExists()
        pressBack()
        compose.onNodeWithText("Remove").assertDoesNotExist()
        assertThat(leftSettings).isEqualTo(0)

        pressBack()
        assertThat(leftSettings).isEqualTo(1)
    }
}
