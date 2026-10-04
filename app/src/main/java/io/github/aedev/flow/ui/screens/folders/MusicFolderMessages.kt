package io.github.aedev.flow.ui.screens.folders

import androidx.annotation.StringRes
import io.github.aedev.flow.R
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.data.folders.NfsAuthenticationRejectedException
import io.github.aedev.flow.data.folders.NfsInsecurePortRequiredException
import io.github.aedev.flow.data.folders.SftpHostKeyMismatchException

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
        is NfsAuthenticationRejectedException -> R.string.music_folders_nfs_auth_rejected
        is SftpHostKeyMismatchException -> R.string.music_folders_host_key_changed
        else -> null
    }
