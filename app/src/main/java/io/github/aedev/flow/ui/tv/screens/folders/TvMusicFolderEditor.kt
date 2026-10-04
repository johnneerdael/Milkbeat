package io.github.aedev.flow.ui.tv.screens.folders

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.ui.screens.folders.FolderAccess
import io.github.aedev.flow.ui.screens.folders.MusicFolderEditor
import io.github.aedev.flow.ui.screens.folders.MusicFoldersViewModel
import io.github.aedev.flow.ui.screens.folders.addLabelRes
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus

@Composable
internal fun TvMusicFolderEditor(
    draft: MusicFolderEditor,
    existing: Boolean,
    viewModel: MusicFoldersViewModel,
) {
    val source = draft.source
    val keyPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::importPrivateKey) }
    LazyColumn(
        modifier = Modifier.fillMaxSize().tvInitialFocus(source.id, onFirstComposition = false).focusGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(
            key = "header",
        ) { TvSectionHeader(stringResource(if (existing) R.string.music_folders_title else source.kind.addLabelRes())) }
        item(key = "back") { TvButton(stringResource(R.string.music_folders_editor_back), viewModel::closeEditor, enabled = !draft.busy) }
        when (source.kind) {
            MusicFolderKind.LOCAL -> {
                item(key = "name") { Text(source.name, style = MaterialTheme.typography.titleMedium) }
            }

            MusicFolderKind.SMB -> {
                smbFields(draft, existing, viewModel)
            }

            MusicFolderKind.WEBDAV -> {
                webDavFields(draft, existing, viewModel)
            }

            MusicFolderKind.SFTP -> {
                sftpFields(draft, existing, viewModel) {
                    try {
                        keyPicker.launch(arrayOf("*/*"))
                    } catch (_: Exception) {
                        viewModel.privateKeyPickerFailed()
                    }
                }
            }

            MusicFolderKind.NFS -> {
                nfsFields(draft, viewModel)
            }
        }
        if (source.kind != MusicFolderKind.LOCAL) {
            item(key = "test") { AccessActions(draft, viewModel) }
        }
        if (existing) {
            item(key = "remove") {
                TvButton(stringResource(R.string.music_folders_remove), { viewModel.remove(source) }, enabled = !draft.busy)
            }
        }
    }
}

@Composable
private fun AccessActions(
    draft: MusicFolderEditor,
    viewModel: MusicFoldersViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val status =
            when (draft.access) {
                FolderAccess.SUCCESS -> R.string.music_folders_access_ok
                FolderAccess.FAILED -> R.string.music_folders_access_failed
                else -> null
            }
        status?.let { Text(stringResource(it), style = MaterialTheme.typography.bodyMedium) }
        draft.error?.let {
            Text(
                stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                stringResource(if (draft.access == FolderAccess.RUNNING) R.string.music_folders_testing else R.string.music_folders_test),
                viewModel::testAccess,
                enabled = !draft.busy,
            )
            TvButton(stringResource(R.string.music_folders_save), viewModel::save, enabled = !draft.busy)
        }
    }
}
