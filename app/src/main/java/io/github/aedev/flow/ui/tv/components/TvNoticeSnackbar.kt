package io.github.aedev.flow.ui.tv.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/** A snackbar message led by an icon, for notices about Milkbeat itself such as a ready update. */
class TvNoticeVisuals(
    override val message: String,
    val icon: ImageVector,
) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Long
}

suspend fun SnackbarHostState.showNotice(
    message: String,
    icon: ImageVector,
) {
    showSnackbar(TvNoticeVisuals(message, icon))
}

/** The host's snackbar: a notice gets its icon, every other message the standard snackbar. */
@Composable
fun TvNoticeSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier,
) {
    val notice = data.visuals as? TvNoticeVisuals
    if (notice == null) {
        Snackbar(data, modifier)
        return
    }
    Snackbar(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = notice.icon, contentDescription = null)
            Text(text = notice.message)
        }
    }
}
