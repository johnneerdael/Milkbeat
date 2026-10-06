package io.github.aedev.flow.ui.tv.screens.folders

import android.app.Application
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.data.folders.MusicFolderRepository
import io.github.aedev.flow.ui.screens.folders.MusicFoldersViewModel
import io.github.aedev.flow.ui.tv.theme.TvTheme
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvMusicFoldersFocusTest {
    @get:Rule val compose = createComposeRule()

    @Test fun returningFromSmbEditorKeepsFocusInMusicFolderPane() {
        val repository = mockk<MusicFolderRepository>()
        every { repository.folders } returns MutableStateFlow(emptyList())
        val viewModel = MusicFoldersViewModel(repository, mockk(relaxed = true), mockk())
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                Row {
                    Button({}, Modifier.width(180.dp)) { Text("Left menu") }
                    androidx.compose.foundation.layout
                        .Box(Modifier.width(650.dp)) { TvMusicFoldersSettingsPane(viewModel) }
                }
            }
        }
        compose.onNodeWithText("Add SMB share").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Add SMB share").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Back").assertIsFocused()
        compose.onNodeWithText("Back").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Choose local folder").assertIsFocused()
    }

    @Test fun testAccessResultIsVisibleWhileItsActionKeepsFocus() {
        val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music", guest = true)
        val repository = mockk<MusicFolderRepository>()
        every { repository.folders } returns MutableStateFlow(listOf(source))
        coEvery { repository.test(any(), any(), any()) } returns source
        val viewModel = MusicFoldersViewModel(repository, mockk(relaxed = true), mockk())
        viewModel.edit(source)
        compose.setContent {
            val input = LocalInputModeManager.current
            LaunchedEffect(Unit) { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                androidx.compose.foundation.layout
                    .Box(Modifier.width(650.dp).height(360.dp)) { TvMusicFoldersSettingsPane(viewModel) }
            }
        }
        compose
            .onNode(
                SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex),
            ).performScrollToNode(
                androidx.compose.ui.test
                    .hasText("Test access"),
            )
        compose.onNodeWithText("Test access").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("Test access").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Access confirmed. This folder can be read.").assertIsDisplayed()
    }
}
