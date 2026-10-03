package io.github.aedev.flow.ui.tv.screens.settings

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.aedev.flow.R

/** Categories of the two-pane TV settings surface. Pure model — unit-testable. */
enum class TvSettingsCategory(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    PLUGINS(R.string.tv_settings_plugins, Icons.Outlined.Extension),
    MUSIC_FOLDERS(R.string.music_folders_title, Icons.Outlined.Folder),
    PLAYBACK(R.string.playback_header, Icons.Outlined.PlayCircleOutline),
    VISUALIZATIONS(R.string.tv_settings_visualizations, Icons.Outlined.GraphicEq),
    ABOUT(R.string.about, Icons.Outlined.Info),
}
