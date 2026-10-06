package io.github.aedev.flow.data.folders

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject

internal class LocalStorageFolders
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        fun hasAccess(): Boolean = localFolderStorageGranted(context)

        fun roots(): List<MusicFolderEntry> {
            check(hasAccess())
            val storage = context.getSystemService(StorageManager::class.java)
            val paths =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    storage.storageVolumes.mapNotNull { it.directory }
                } else {
                    // StorageVolume.directory is public only from API 30; validate older mount paths through StorageManager.
                    listOf(Environment.getExternalStorageDirectory()) +
                        File("/storage").listFiles().orEmpty().filter {
                            storage.getStorageVolume(it)?.isRemovable == true
                        }
                }
            return paths
                .mapNotNull { path ->
                    val volume = storage.getStorageVolume(path) ?: return@mapNotNull null
                    if (volume.state !in setOf(Environment.MEDIA_MOUNTED, Environment.MEDIA_MOUNTED_READ_ONLY) || !path.isDirectory) {
                        return@mapNotNull null
                    }
                    MusicFolderEntry(volume.getDescription(context), Uri.fromFile(path.canonicalFile).toString(), true)
                }.distinctBy { it.location }
        }
    }

internal fun localFolderStorageGranted(context: Context): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
