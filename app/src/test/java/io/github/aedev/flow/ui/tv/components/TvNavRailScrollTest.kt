package io.github.aedev.flow.ui.tv.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.ui.tv.navigation.TvDestination
import io.github.aedev.flow.ui.tv.navigation.TvTab
import io.github.aedev.flow.ui.tv.theme.TvTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w960dp-h540dp-land-mdpi")
class TvNavRailScrollTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun `with five music tabs the fixed tabs below them stay reachable on a 540dp screen`() {
        val music =
            listOf("Beatport", "SoundCloud", "Spotify", "YouTube Music", "Local library").map { label ->
                TvRailItem(TvTab.Music(MusicSource.Plugin(label)), label, TvRailIcon.Symbol(Icons.Outlined.MusicNote))
            }
        val fixed =
            TvDestination.fixed.map { destination ->
                TvRailItem(TvTab.Fixed(destination), destination.name, TvRailIcon.Symbol(destination.icon))
            }
        lateinit var focus: FocusRequester
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            focus = remember { FocusRequester() }
            TvTheme {
                Box(Modifier.height(540.dp)) {
                    TvNavRail(
                        items = music + fixed,
                        selected = music.first().tab,
                        onSelected = {},
                        onFocusChanged = {},
                        selectedFocusRequester = focus,
                    )
                }
            }
        }
        compose.runOnIdle { focus.requestFocus() }
        repeat(music.size + fixed.size - 1) {
            compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
            compose.waitForIdle()
        }

        val settings = compose.onNodeWithContentDescription(TvDestination.SETTINGS.name)
        settings.assertIsDisplayed()
        // A full entry, as tall as the first, not squeezed into what the column had left.
        val first = compose.onNodeWithContentDescription("Beatport").getUnclippedBoundsInRoot()
        val last = settings.getUnclippedBoundsInRoot()
        assertThat(last.bottom - last.top).isEqualTo(first.bottom - first.top)
        assertThat(last.bottom).isAtMost(540.dp)
    }
}
