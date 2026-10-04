package io.github.aedev.flow.data.library.index

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.R
import kotlin.coroutines.cancellation.CancellationException

internal const val SCAN_READ = "read"
internal const val SCAN_TOTAL = "total"
private const val CHANNEL_ID = "library_scan"
private const val NOTIFICATION_ID = 8606
private const val PROGRESS_INTERVAL_MS = 1_000L
private const val MAX_ATTEMPTS = 3
private const val TAG = "LibraryScanWorker"

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface LibraryScanEntryPoint {
    fun libraryIndexer(): LibraryIndexer
}

/** Runs one scan of the music folders into the library index, in the foreground so a first scan of thousands of files can finish. */
class LibraryScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.library_scan_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        try {
            setForeground(foreground(LibraryScanProgress(0, 0)))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground start from the background; the scan still runs, and resumes if stopped.
            Log.w(TAG, "Scanning without a foreground notification", e)
        }
        val indexer = EntryPointAccessors.fromApplication(applicationContext, LibraryScanEntryPoint::class.java).libraryIndexer()
        var lastProgressAt = 0L
        return try {
            val result =
                indexer.scan { progress ->
                    val now = SystemClock.elapsedRealtime()
                    if (progress.read == progress.total || now - lastProgressAt >= PROGRESS_INTERVAL_MS) {
                        setProgress(workDataOf(SCAN_READ to progress.read, SCAN_TOTAL to progress.total))
                        manager.notify(NOTIFICATION_ID, notification(progress))
                        lastProgressAt = now
                    }
                }
            if (result.complete || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Library scan failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    private fun foreground(progress: LibraryScanProgress): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification(progress))
        }

    private fun notification(progress: LibraryScanProgress): Notification =
        NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(applicationContext.getString(R.string.library_scan_notification_title))
            .setContentText(applicationContext.getString(R.string.library_scan_progress, progress.read, progress.total))
            .setProgress(progress.total, progress.read, progress.total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
