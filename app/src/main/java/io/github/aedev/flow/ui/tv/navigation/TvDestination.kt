package io.github.aedev.flow.ui.tv.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.aedev.flow.R

/**
 * Top-level destinations of the TV navigation graph. [MUSIC] hosts every music tab of the rail; the
 * [fixed] destinations follow them.
 */
enum class TvDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    MUSIC("music", R.string.nav_music, Icons.Outlined.MusicNote),
    SEARCH("search", R.string.search, Icons.Outlined.Search),
    LIBRARY("library", R.string.library, Icons.Outlined.VideoLibrary),
    SETTINGS("settings", R.string.settings, Icons.Outlined.Settings),
    ;

    companion object {
        val fixed: List<TvDestination> = listOf(SEARCH, LIBRARY, SETTINGS)

        /** The destination the app opens on and Back converges to: the music tabs. */
        val start: TvDestination = MUSIC

        fun fromRoute(route: String?): TvDestination =
            if (route ==
                TvRoutes.MUSIC_FOLDERS_SETTINGS
            ) {
                SETTINGS
            } else {
                entries.firstOrNull { it.route == route } ?: start
            }
    }
}
