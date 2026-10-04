package io.github.aedev.flow.plugin.preload

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
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.background.BackgroundPausedException
import io.github.aedev.flow.plugin.background.BackgroundProviderBackoff
import io.github.aedev.flow.plugin.background.ProviderPause
import io.github.aedev.flow.plugin.runtime.PluginCallException
import nl.neerdael.milkbeat.plugin.PluginErrorCode

internal const val PRELOAD_PLUGIN = "plugin"
internal const val PRELOAD_ACCOUNT = "account"
internal const val PRELOAD_AUDIO = "audio"
internal const val PRELOAD_ERROR = "error"
internal const val PRELOAD_PAUSED_UNTIL = "pausedUntil"
private const val CHANNEL_ID = "playlist_preload"

internal fun preloadWorkName(pluginId: String): String = "playlist-preload:$pluginId"

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaylistPreloadEntryPoint {
    fun playlistPreloadRunner(): PlaylistPreloadRunner

    fun playlistPreloadBackoff(): BackgroundProviderBackoff
}

class PlaylistPreloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private val notificationId: Int get() = 8505 + (inputData.getString(PRELOAD_PLUGIN).orEmpty().hashCode() and 0x00ffffff)

    override suspend fun doWork(): Result {
        val plugin = inputData.getString(PRELOAD_PLUGIN) ?: return Result.failure()
        val account = inputData.getString(PRELOAD_ACCOUNT) ?: return Result.failure()
        val audio = inputData.getStringArray(PRELOAD_AUDIO)?.toList() ?: return Result.failure()
        val entry = EntryPointAccessors.fromApplication(applicationContext, PlaylistPreloadEntryPoint::class.java)
        val backoff = entry.playlistPreloadBackoff()
        backoff.activePause(audio + plugin)?.let { return resumeAfter(plugin, it, backoff.now()) }
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                applicationContext.getString(R.string.playlist_preload_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        setForeground(foreground(PlaylistPreloadProgress()))
        val runner = entry.playlistPreloadRunner()
        var lastProgressAt = 0L
        return try {
            val result =
                runner.run(plugin, account, audio) { progress ->
                    val now = SystemClock.elapsedRealtime()
                    if (progress.finished || now - lastProgressAt >= 1_000L) {
                        setProgress(progress.data())
                        manager.notify(notificationId, notification(progress))
                        lastProgressAt = now
                    }
                }
            Result.success(result.data())
        } catch (e: BackgroundPausedException) {
            resumeAfter(plugin, e.pause, backoff.now())
        } catch (e: PlaylistPreloadException) {
            Result.failure(workDataOf(PRELOAD_ERROR to e.reason.name))
        } catch (e: PluginCallException) {
            Log.w("PlaylistPreloadWorker", "Indexing failed via ${e.pluginId} (${e.error.code}): ${e.error.message}")
            if (e.error.code in setOf(PluginErrorCode.NETWORK, PluginErrorCode.TIMEOUT) &&
                runAttemptCount < 3
            ) {
                Result.retry()
            } else {
                Result.failure(workDataOf(PRELOAD_ERROR to e.error.code.name))
            }
        }
    }

    /**
     * Ends this run without waiting on the pause: the same job is chained after it, to start once
     * [pause] ends, and cached matches let it continue where this run stopped.
     */
    private suspend fun resumeAfter(
        plugin: String,
        pause: ProviderPause,
        now: Long,
    ): Result {
        Log.i("PlaylistPreloadWorker", "Indexing paused by ${pause.pluginId}; resuming in ${(pause.untilMs - now) / 1000}s")
        WorkManager
            .getInstance(applicationContext)
            .enqueueUniqueWork(preloadWorkName(plugin), ExistingWorkPolicy.APPEND_OR_REPLACE, preloadRequest(inputData, pause.untilMs - now))
            .await()
        return Result.success(workDataOf(PRELOAD_PAUSED_UNTIL to pause.untilMs))
    }

    private fun foreground(progress: PlaylistPreloadProgress): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notificationId, notification(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification(progress))
        }

    private fun notification(progress: PlaylistPreloadProgress): Notification =
        NotificationCompat
            .Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_logo)
            .setContentTitle(applicationContext.getString(R.string.playlist_preload_notification_title))
            .setContentText(
                applicationContext.getString(R.string.playlist_preload_running, progress.indexed, progress.matched, progress.unavailable),
            ).setOngoing(!progress.finished)
            .setOnlyAlertOnce(true)
            .build()
}

internal fun PlaylistPreloadProgress.data(): Data =
    workDataOf(
        "collections" to collections,
        "scanned" to scannedCollections,
        "indexed" to indexed,
        "matched" to matched,
        "unavailable" to unavailable,
        "finished" to finished,
    )

internal fun Data.preloadProgress(): PlaylistPreloadProgress =
    PlaylistPreloadProgress(
        getInt("collections", 0),
        getInt("scanned", 0),
        getInt("indexed", 0),
        getInt("matched", 0),
        getInt("unavailable", 0),
        getBoolean("finished", false),
    )
