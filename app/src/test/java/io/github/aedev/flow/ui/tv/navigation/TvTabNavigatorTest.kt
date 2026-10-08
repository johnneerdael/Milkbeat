package io.github.aedev.flow.ui.tv.navigation

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Replays rail presses and Back on a real NavController, as the shell makes them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class TvTabNavigatorTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController

    private fun start() {
        compose.setContent {
            nav = rememberNavController()
            NavHost(nav, startDestination = TvDestination.MUSIC.route) {
                TvDestination.entries.forEach { composable(it.route) {} }
                composable(ALBUM) {}
                composable(ARTIST) {}
                composable(TvRoutes.MUSIC_FOLDERS_SETTINGS) {}
            }
        }
        compose.waitForIdle()
    }

    // The graph's own entry, without a route, is left out.
    private val routes: List<String> get() = nav.currentBackStack.value.mapNotNull { it.destination.route }

    private fun press(destination: TvDestination) {
        compose.runOnIdle { nav.navigateToTab(destination, ownerDestination(routes)) }
        compose.waitForIdle()
    }

    private fun open(route: String) {
        compose.runOnIdle { nav.navigate(route) }
        compose.waitForIdle()
    }

    private val top: String? get() = routes.last()

    @Test
    fun `Back from Settings and every music press after it show the music tab, not the album left behind`() {
        start()
        open(ALBUM)
        press(TvDestination.SETTINGS)
        assertThat(top).isEqualTo(TvDestination.SETTINGS.route)

        // Back from Settings returns to the music tabs.
        press(TvDestination.MUSIC)
        assertThat(top).isEqualTo(TvDestination.MUSIC.route)
        assertThat(ownerDestination(routes)).isEqualTo(TvDestination.MUSIC)

        press(TvDestination.MUSIC)
        assertThat(routes).containsExactly(TvDestination.MUSIC.route)
    }

    @Test
    fun `pressing the music tab on a music page returns to the music tab`() {
        start()
        open(ALBUM)
        press(TvDestination.MUSIC)
        assertThat(routes).containsExactly(TvDestination.MUSIC.route)
    }

    @Test
    fun `a fixed tab keeps its open page while another tab is shown`() {
        start()
        press(TvDestination.LIBRARY)
        open(ARTIST)
        press(TvDestination.SEARCH)
        assertThat(top).isEqualTo(TvDestination.SEARCH.route)

        press(TvDestination.LIBRARY)
        assertThat(top).isEqualTo(ARTIST)
        assertThat(ownerDestination(routes)).isEqualTo(TvDestination.LIBRARY)

        press(TvDestination.MUSIC)
        assertThat(routes).containsExactly(TvDestination.MUSIC.route)
        press(TvDestination.LIBRARY)
        assertThat(top).isEqualTo(ARTIST)
    }

    @Test
    fun `pressing the tab that owns the page returns to its root`() {
        start()
        press(TvDestination.SEARCH)
        open(ARTIST)
        press(TvDestination.SEARCH)
        assertThat(routes).containsExactly(TvDestination.MUSIC.route, TvDestination.SEARCH.route).inOrder()
    }

    @Test
    fun `a music page left for another tab never comes back under the music tab of another tab's pages`() {
        start()
        press(TvDestination.LIBRARY)
        open(ARTIST)
        press(TvDestination.SEARCH)
        press(TvDestination.MUSIC)
        assertThat(routes).containsExactly(TvDestination.MUSIC.route)
    }

    @Test
    fun `the folders settings page opened from the music tab reaches Settings`() {
        start()
        open(TvRoutes.MUSIC_FOLDERS_SETTINGS)
        press(TvDestination.SETTINGS)
        assertThat(top).isEqualTo(TvDestination.SETTINGS.route)
    }

    private companion object {
        const val ALBUM = "test-album"
        const val ARTIST = "test-artist"
    }
}
