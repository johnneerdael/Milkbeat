package io.github.aedev.flow.ui.tv.screens.search

import androidx.activity.ComponentActivity
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.aedev.flow.ui.tv.catalog.TvCatalogActions
import io.github.aedev.flow.ui.tv.theme.TvTheme
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class TvSearchStripFocusDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun appendedPageDoesNotMoveTheExitAwayFromTheFocusedLastRow() {
        var count by mutableStateOf(1)
        val actions = TvCatalogActions({ null }, {}, { _, _, _, _ -> }, {})
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            val strip = remember { FocusRequester() }
            val blocks =
                listOf(
                    CollectionBlock(
                        id = "results",
                        header = null,
                        layout = CollectionLayout.HORIZONTAL_SHELF,
                        defaultItemView = ItemView.COVER_CARD,
                        items = (0 until count).map { MetadataItem("$it", EntityRef(EntityKind.ALBUM, "$it"), "Result $it") },
                    ),
                )
            TvTheme {
                Column(Modifier.fillMaxSize()) {
                    TvSearchResultsPane(
                        query = "test",
                        results = TvSearchResults(query = "test", loaded = true, blocks = blocks),
                        actions = actions,
                        onLoadMore = {},
                        stripFocus = strip,
                        modifier =
                            Modifier
                                .weight(1f)
                                .focusProperties {
                                    exit = { if (it == FocusDirection.Down) strip else FocusRequester.Default }
                                }.focusGroup(),
                    )
                    Button(onClick = {}, modifier = Modifier.focusRequester(strip)) { Text("Now playing") }
                }
            }
        }
        compose.onNodeWithText("Result 0").performSemanticsAction(SemanticsActions.RequestFocus)
        compose.onNodeWithText("Result 0").assertIsFocused()
        compose.runOnIdle { count = 7 }
        compose.onNodeWithText("Result 0").performKeyInput { pressKey(Key.DirectionDown) }
        compose.onNodeWithText("Now playing").assertIsFocused()
    }
}
