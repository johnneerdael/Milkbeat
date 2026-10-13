package io.github.aedev.flow.plugin.catalog

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import kotlinx.coroutines.flow.first

/**
 * Removes what the retired playlist mirror left on a device: its scheduled work, notification
 * channel and saved state. The private copies it made stay in the listener's account.
 */
internal object RetiredPlaylistMirrors {
    private const val WORK_TAG_PREFIX = "mirror:"
    private const val NOTIFICATION_CHANNEL = "playlist_mirror"
    private const val DATA_STORE = "playlist_mirrors"

    suspend fun clean(
        context: Context,
        work: WorkManager = WorkManager.getInstance(context),
    ) {
        // Work tags cannot be queried by prefix; each pair had its own tag.
        work
            .getWorkInfosFlow(
                WorkQuery.fromStates(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED),
            ).first()
            .filter { info -> info.tags.any { it.startsWith(WORK_TAG_PREFIX) } }
            .forEach { work.cancelWorkById(it.id) }
        NotificationManagerCompat.from(context).deleteNotificationChannel(NOTIFICATION_CHANNEL)
        context.preferencesDataStoreFile(DATA_STORE).delete()
    }
}
