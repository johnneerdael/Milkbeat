package io.github.aedev.flow.ui.tv.screens.folders

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.ui.screens.folders.LibraryScanViewModel
import io.github.aedev.flow.ui.screens.folders.MusicFoldersViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvLibraryScanStatus
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus

@Composable
internal fun TvMusicFoldersSettingsPane(viewModel: MusicFoldersViewModel = hiltViewModel()) {
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(viewModel::addLocal) }
    BackHandler(editor != null) { viewModel.closeEditor() }
    ProvideTvColumnPivot {
        Box(Modifier.fillMaxSize().tvInitialFocus(editor?.source?.id, onFirstComposition = false).focusGroup()) {
            val draft = editor
            if (draft != null) {
                TvMusicFolderEditor(draft, folders.any { it.id == draft.source.id }, viewModel)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    item(key = "header") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            TvSectionHeader(stringResource(R.string.music_folders_title))
                            Text(
                                stringResource(R.string.music_folders_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item(key = "local") {
                        TvButton(stringResource(R.string.music_folders_add_local), onClick = {
                            try {
                                picker.launch(null)
                            } catch (_: Exception) {
                                viewModel.pickerFailed()
                            }
                        })
                    }
                    item(key = "smb") { TvButton(stringResource(R.string.music_folders_add_smb), { viewModel.edit() }) }
                    if (folders.isNotEmpty()) {
                        item(key = "library-scan") {
                            val scanViewModel: LibraryScanViewModel = hiltViewModel()
                            val scan by scanViewModel.state.collectAsStateWithLifecycle()
                            TvLibraryScanStatus(scan = scan, onRescan = scanViewModel::rescan)
                        }
                    }
                    message?.let { res -> item(key = "message") { Text(stringResource(res), style = MaterialTheme.typography.bodyMedium) } }
                    items(folders, key = { it.id }) { source ->
                        TvNavRow(
                            label = source.name,
                            value =
                                stringResource(
                                    if (source.kind ==
                                        MusicFolderKind.LOCAL
                                    ) {
                                        R.string.music_folders_local
                                    } else {
                                        R.string.music_folders_smb
                                    },
                                ),
                            leadingIcon = Icons.Outlined.Folder,
                            onClick = { viewModel.edit(source) },
                        )
                    }
                }
            }
        }
    }
}
