package io.github.aedev.flow.ui.tv.screens.folders

import androidx.annotation.StringRes
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.NfsVersion
import io.github.aedev.flow.ui.screens.folders.MusicFolderEditor
import io.github.aedev.flow.ui.screens.folders.MusicFoldersViewModel
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSearchField
import io.github.aedev.flow.ui.tv.components.TvToggleRow

internal fun LazyListScope.smbFields(
    draft: MusicFolderEditor,
    existing: Boolean,
    viewModel: MusicFoldersViewModel,
) {
    nameField(draft, viewModel)
    serverFields(draft, viewModel, R.string.music_folders_port)
    sourceField("share", draft.source.share, R.string.music_folders_share, viewModel) { copy(share = it) }
    sourceField("path", draft.source.root, R.string.music_folders_path, viewModel) { copy(root = it) }
    guestToggle(draft, viewModel, R.string.music_folders_guest)
    if (!draft.source.guest) {
        sourceField("username", draft.source.username, R.string.music_folders_username, viewModel) { copy(username = it) }
        sourceField("domain", draft.source.domain, R.string.music_folders_domain, viewModel) { copy(domain = it) }
        passwordField(draft, existing, viewModel)
    }
}

internal fun LazyListScope.webDavFields(
    draft: MusicFolderEditor,
    existing: Boolean,
    viewModel: MusicFoldersViewModel,
) {
    nameField(draft, viewModel)
    sourceField("url", draft.source.url, R.string.music_folders_url, viewModel, KeyboardType.Uri) { copy(url = it) }
    guestToggle(draft, viewModel, R.string.music_folders_anonymous)
    if (!draft.source.guest) {
        sourceField("username", draft.source.username, R.string.music_folders_username, viewModel) { copy(username = it) }
        passwordField(draft, existing, viewModel)
        if (draft.source.webDavUrl()?.isHttps == false) {
            item(key = "cleartext") {
                Text(
                    stringResource(R.string.music_folders_webdav_cleartext),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

internal fun LazyListScope.sftpFields(
    draft: MusicFolderEditor,
    existing: Boolean,
    viewModel: MusicFoldersViewModel,
    onChooseKey: () -> Unit,
) {
    val source = draft.source
    nameField(draft, viewModel)
    serverFields(draft, viewModel, R.string.music_folders_port_sftp)
    sourceField("path", source.root, R.string.music_folders_sftp_path, viewModel) { copy(root = it) }
    sourceField("username", source.username, R.string.music_folders_username, viewModel) { copy(username = it) }
    item(key = "key_auth") {
        TvToggleRow(stringResource(R.string.music_folders_private_key_auth), source.keyAuth, {
            viewModel.updateDraft { copy(source = source.copy(keyAuth = it)) }
        })
    }
    if (source.keyAuth) {
        item(key = "key_file") {
            val state =
                when {
                    !draft.privateKey.isNullOrEmpty() -> R.string.music_folders_private_key_loaded
                    draft.privateKey == null && draft.hasSavedPrivateKey -> R.string.music_folders_private_key_saved
                    else -> R.string.music_folders_private_key_missing
                }
            TvNavRow(
                stringResource(R.string.music_folders_private_key_choose),
                onChooseKey,
                value = stringResource(state),
                leadingIcon = Icons.Outlined.Key,
            )
        }
        passwordField(draft, existing, viewModel, R.string.music_folders_passphrase, R.string.music_folders_passphrase_keep)
    } else {
        passwordField(draft, existing, viewModel)
    }
    item(key = "host_key") {
        if (source.hostKey.isBlank()) {
            Text(
                stringResource(R.string.music_folders_host_key_unknown),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(stringResource(R.string.music_folders_host_key, source.hostKey), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (source.hostKey.isNotBlank()) {
        item(key = "host_key_forget") {
            TvButton(stringResource(R.string.music_folders_host_key_forget), {
                viewModel.updateDraft { copy(source = source.copy(hostKey = "")) }
            }, enabled = !draft.busy)
        }
    }
}

internal fun LazyListScope.nfsFields(
    draft: MusicFolderEditor,
    viewModel: MusicFoldersViewModel,
) {
    val source = draft.source
    item(key = "nfs_hint") {
        Text(
            stringResource(R.string.music_folders_nfs_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    nameField(draft, viewModel)
    serverFields(draft, viewModel, R.string.music_folders_port_nfs)
    sourceField("export", source.share, R.string.music_folders_nfs_export, viewModel) { copy(share = it) }
    sourceField("path", source.root, R.string.music_folders_nfs_path, viewModel) { copy(root = it) }
    item(key = "nfs_version") {
        TvNavRow(
            stringResource(R.string.music_folders_nfs_version),
            {
                val versions = NfsVersion.entries
                viewModel.updateDraft { copy(source = source.copy(nfsVersion = versions[(source.nfsVersion.ordinal + 1) % versions.size])) }
            },
            value = stringResource(source.nfsVersion.labelRes()),
        )
    }
    item(key = "uid") {
        FolderField(draft.uid, R.string.music_folders_nfs_uid, KeyboardType.Number) { viewModel.updateDraft { copy(uid = it) } }
    }
    item(key = "gid") {
        FolderField(draft.gid, R.string.music_folders_nfs_gid, KeyboardType.Number) { viewModel.updateDraft { copy(gid = it) } }
    }
}

@StringRes
private fun NfsVersion.labelRes(): Int =
    when (this) {
        NfsVersion.AUTO -> R.string.music_folders_nfs_version_auto
        NfsVersion.V3 -> R.string.music_folders_nfs_version_v3
        NfsVersion.V4 -> R.string.music_folders_nfs_version_v4
        NfsVersion.V4_1 -> R.string.music_folders_nfs_version_v4_1
    }

private fun LazyListScope.nameField(
    draft: MusicFolderEditor,
    viewModel: MusicFoldersViewModel,
) = sourceField("name", draft.source.name, R.string.music_folders_name, viewModel) { copy(name = it) }

private fun LazyListScope.serverFields(
    draft: MusicFolderEditor,
    viewModel: MusicFoldersViewModel,
    @StringRes portHint: Int,
) {
    sourceField("server", draft.source.host, R.string.music_folders_server, viewModel) { copy(host = it, hostKey = "") }
    item(key = "port") {
        FolderField(draft.port, portHint, KeyboardType.Number) {
            viewModel.updateDraft { copy(port = it, source = source.copy(hostKey = "")) }
        }
    }
}

private fun LazyListScope.sourceField(
    key: String,
    value: String,
    @StringRes hint: Int,
    viewModel: MusicFoldersViewModel,
    keyboard: KeyboardType = KeyboardType.Text,
    change: MusicFolder.(String) -> MusicFolder,
) {
    item(key = key) {
        FolderField(value, hint, keyboard) { text -> viewModel.updateDraft { copy(source = source.change(text)) } }
    }
}

private fun LazyListScope.guestToggle(
    draft: MusicFolderEditor,
    viewModel: MusicFoldersViewModel,
    @StringRes label: Int,
) {
    item(key = "guest") {
        TvToggleRow(stringResource(label), draft.source.guest, {
            viewModel.updateDraft { copy(source = source.copy(guest = it)) }
        })
    }
}

private fun LazyListScope.passwordField(
    draft: MusicFolderEditor,
    existing: Boolean,
    viewModel: MusicFoldersViewModel,
    @StringRes label: Int = R.string.music_folders_password,
    @StringRes keepLabel: Int = R.string.music_folders_password_keep,
) {
    item(key = "password") {
        TvSearchField(
            query = draft.password.orEmpty(),
            onQueryChange = { viewModel.updateDraft { copy(password = it) } },
            onSearch = {},
            placeholder = stringResource(if (existing && draft.password == null) keepLabel else label),
            label = stringResource(label),
            secure = true,
            imeAction = ImeAction.Done,
            leadingIcon = Icons.Outlined.Folder,
        )
    }
}

@Composable
private fun FolderField(
    value: String,
    @StringRes hint: Int,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit,
) {
    TvSearchField(
        value,
        onChange,
        onSearch = {},
        placeholder = stringResource(hint),
        label = stringResource(hint),
        leadingIcon = Icons.Outlined.Folder,
        keyboardType = keyboard,
        imeAction = ImeAction.Done,
    )
}
