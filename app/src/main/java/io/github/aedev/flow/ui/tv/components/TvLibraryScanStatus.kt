package io.github.aedev.flow.ui.tv.components

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.library.index.LibraryScanState

/**
 * Where the local library's scan of the music folders is, with a button to run it again. While the
 * first scan runs, the home shows this in place of its shelves.
 */
@Composable
internal fun TvLibraryScanStatus(
    scan: LibraryScanState,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier,
    actionModifier: Modifier = Modifier,
    @StringRes idleTitle: Int = R.string.local_library_title,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(if (scan.running) R.string.local_library_indexing else idleTitle),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text =
                if (scan.running && scan.total > 0) {
                    stringResource(R.string.local_library_indexing_progress, scan.read, scan.total)
                } else {
                    stringResource(R.string.local_library_scan_idle)
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TvButton(text = stringResource(R.string.local_library_rescan), onClick = onRescan, modifier = actionModifier)
    }
}
