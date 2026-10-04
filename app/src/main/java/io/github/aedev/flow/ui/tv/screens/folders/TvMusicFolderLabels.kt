package io.github.aedev.flow.ui.tv.screens.folders

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.MusicFolderKind

@StringRes
internal fun MusicFolderKind.labelRes(): Int =
    when (this) {
        MusicFolderKind.LOCAL -> R.string.music_folders_local
        MusicFolderKind.SMB -> R.string.music_folders_smb
        MusicFolderKind.WEBDAV -> R.string.music_folders_webdav
        MusicFolderKind.SFTP -> R.string.music_folders_sftp
        MusicFolderKind.NFS -> R.string.music_folders_nfs
    }

@StringRes
internal fun MusicFolderKind.addLabelRes(): Int =
    when (this) {
        MusicFolderKind.LOCAL -> R.string.music_folders_add_local
        MusicFolderKind.SMB -> R.string.music_folders_add_smb
        MusicFolderKind.WEBDAV -> R.string.music_folders_add_webdav
        MusicFolderKind.SFTP -> R.string.music_folders_add_sftp
        MusicFolderKind.NFS -> R.string.music_folders_add_nfs
    }
