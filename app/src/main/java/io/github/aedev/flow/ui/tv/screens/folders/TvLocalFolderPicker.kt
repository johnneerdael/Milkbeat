package io.github.aedev.flow.ui.tv.screens.folders

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.screens.folders.LocalFolderSelection
import io.github.aedev.flow.ui.screens.folders.MusicFoldersViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLoadingState
import io.github.aedev.flow.ui.tv.components.TvMessageState
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus

@Composable
internal fun TvLocalFolderPicker(
    state: LocalFolderSelection,
    viewModel: MusicFoldersViewModel,
) {
    val folder = state.stack.lastOrNull()
    Column(
        modifier = Modifier.fillMaxSize().tvInitialFocus(folder?.path, state.loading).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TvSectionHeader(folder?.name ?: stringResource(R.string.music_folders_choose_drive))
        folder?.let {
            Text(
                android.net.Uri
                    .parse(it.path)
                    .path
                    .orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(stringResource(R.string.music_folders_editor_back), viewModel::localPickerBack, enabled = !state.saving)
            TvButton(
                stringResource(R.string.music_folders_refresh),
                viewModel::refreshLocalPicker,
                enabled =
                    !state.loading && !state.saving,
            )
            if (folder != null) {
                TvButton(
                    stringResource(R.string.music_folders_choose_directory),
                    viewModel::chooseLocalFolder,
                    enabled = !state.loading && !state.failed && !state.saving,
                )
            }
        }
        when {
            state.loading || state.saving -> {
                TvLoadingState(Modifier.weight(1f))
            }

            state.failed -> {
                TvMessageState(stringResource(R.string.music_folders_browse_failed), modifier = Modifier.weight(1f))
            }

            state.entries.isEmpty() -> {
                TvMessageState(
                    stringResource(if (folder == null) R.string.music_folders_no_drives else R.string.music_folders_no_subfolders),
                    modifier = Modifier.weight(1f),
                )
            }

            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.entries, key = { it.location }) { entry ->
                        TvNavRow(
                            label = entry.name,
                            onClick = { viewModel.openLocalDirectory(entry) },
                            leadingIcon = Icons.Outlined.Folder,
                        )
                    }
                }
            }
        }
    }
}
