package io.github.aedev.flow.ui.screens.folders

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.data.folders.NfsInsecurePortRequiredException
import io.github.aedev.flow.data.folders.SftpHostKeyMismatchException

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

@StringRes
internal fun invalidMessage(kind: MusicFolderKind): Int =
    when (kind) {
        MusicFolderKind.WEBDAV -> R.string.music_folders_invalid_webdav
        MusicFolderKind.SFTP, MusicFolderKind.NFS -> R.string.music_folders_invalid_remote
        else -> R.string.music_folders_invalid
    }

@StringRes
internal fun folderAccessMessage(error: Throwable): Int? =
    when (error) {
        is NfsInsecurePortRequiredException -> R.string.music_folders_nfs_insecure_required
        is SftpHostKeyMismatchException -> R.string.music_folders_host_key_changed
        else -> null
    }
