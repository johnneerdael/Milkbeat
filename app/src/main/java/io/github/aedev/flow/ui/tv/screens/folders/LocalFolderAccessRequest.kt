package io.github.aedev.flow.ui.tv.screens.folders

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import io.github.aedev.flow.data.folders.localFolderStorageGranted

internal class LocalFolderAccessRequest(
    val choose: () -> Unit,
    val settings: () -> Unit,
)

@Composable
internal fun rememberLocalFolderAccessRequest(
    onGranted: () -> Unit,
    onDenied: () -> Unit,
): LocalFolderAccessRequest {
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onGranted() else onDenied()
        }
    val settings =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (localFolderStorageGranted(context)) onGranted() else onDenied()
        }
    val openSettings: () -> Unit = {
        val action =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
            } else {
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            }
        try {
            settings.launch(Intent(action, Uri.fromParts("package", context.packageName, null)))
        } catch (_: ActivityNotFoundException) {
            try {
                settings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: ActivityNotFoundException) {
                onDenied()
            }
        }
    }
    return LocalFolderAccessRequest(
        choose = {
            when {
                localFolderStorageGranted(context) -> onGranted()
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> openSettings()
                else -> permission.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        },
        settings = openSettings,
    )
}
