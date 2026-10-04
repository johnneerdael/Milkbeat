package io.github.aedev.flow.plugin.mirror

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.background.BackgroundPausedException
import io.github.aedev.flow.plugin.background.BackgroundProviderBackoff
import io.github.aedev.flow.plugin.background.ProviderPause
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PersonalCollectionsRequest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations

@EntryPoint
@InstallIn(SingletonComponent::class)
interface PlaylistMirrorEntryPoint {
    fun mirrorCoordinator(): PlaylistMirrorCoordinator

    fun mirrorHost(): PluginHost

    fun mirrorAccounts(): PluginAccounts

    fun mirrorBackoff(): BackgroundProviderBackoff
}

class PlaylistMirrorWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val source = inputData.getString("source") ?: return Result.failure()
        val target = inputData.getString("target") ?: return Result.failure()
        val sourceAccount = inputData.getString("sourceAccount") ?: return Result.failure()
        val targetAccount = inputData.getString("targetAccount") ?: return Result.failure()
        val entry = EntryPointAccessors.fromApplication(applicationContext, PlaylistMirrorEntryPoint::class.java)
        val coordinator = entry.mirrorCoordinator()
        val host = entry.mirrorHost()
        val accounts = entry.mirrorAccounts()
        val backoff = entry.mirrorBackoff()
        val mirrorId = MirrorKey(source, sourceAccount, target, targetAccount, EntityRef(EntityKind.PLAYLIST, "owned")).id
        backoff.activePause(listOf(source, target))?.let { return resumeAfter(mirrorId, it, backoff.now()) }
        return try {
            for (id in listOf(source, target)) if (accounts.accounts.value[id] == null) accounts.refresh(id)
            if ((accounts.accounts.value[source] as? ProviderAccount.SignedIn)?.key != sourceAccount ||
                (accounts.accounts.value[target] as? ProviderAccount.SignedIn)?.key != targetAccount ||
                PlaylistMirrorStore.pairId(source, target) !in coordinator.store.enabledPairs.first()
            ) {
                return Result.failure()
            }
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    "playlist_mirror",
                    applicationContext.getString(R.string.playlist_mirror_title),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
            val notification =
                NotificationCompat
                    .Builder(applicationContext, "playlist_mirror")
                    .setSmallIcon(R.drawable.ic_notification_logo)
                    .setContentTitle(
                        applicationContext.getString(R.string.playlist_mirror_title),
                    ).setContentText(applicationContext.getString(R.string.playlist_mirror_background))
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            setForeground(
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.Q
                ) {
                    ForegroundInfo(8506, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    ForegroundInfo(8506, notification)
                },
            )
            var cursor: String? = null
            val cursors = mutableSetOf<String>()
            do {
                backoff.ensureNotPaused(source)
                val page =
                    try {
                        withContext(NonCancellable) {
                            host.call(source, PluginOperations.personalCollections, PersonalCollectionsRequest(cursor, sourceAccount))
                        }
                    } catch (e: PluginCallException) {
                        backoff.throwIfRefused(e)
                        throw e
                    }
                currentCoroutineContext().ensureActive()
                prepareMirrorCollections(page.collections) { collection ->
                    if (PlaylistMirrorStore.pairId(source, target) !in
                        coordinator.store.enabledPairs.first()
                    ) {
                        throw MirrorPreparationException(MirrorFailure.ACCOUNT_CHANGED)
                    }
                    val key =
                        coordinator.key(source, target, collection.ref) ?: throw MirrorPreparationException(MirrorFailure.ACCOUNT_CHANGED)
                    if (key.sourceAccount != sourceAccount ||
                        key.targetAccount != targetAccount
                    ) {
                        throw MirrorPreparationException(MirrorFailure.ACCOUNT_CHANGED)
                    }
                    coordinator.prepare(key, collection.title, background = true, artwork = collection.artwork)
                }
                cursor = page.next
                if (cursor != null && !cursors.add(cursor)) return Result.failure()
            } while (cursor != null)
            backoff.succeeded(listOf(source, target))
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackgroundPausedException) {
            resumeAfter(mirrorId, e.pause, backoff.now())
        } catch (e: MirrorPreparationException) {
            if (e.reason == MirrorFailure.SOURCE_CHANGED) Result.retry() else Result.failure()
        } catch (e: PluginCallException) {
            if (e.error.code == PluginErrorCode.SIGN_IN_EXPIRED) accounts.expired(e.pluginId)
            if (e.error.code == PluginErrorCode.RATE_LIMITED) return resumeAfter(mirrorId, backoff.refused(e.pluginId, e.error.retryAfterMs), backoff.now())
            if (e.error.code in setOf(PluginErrorCode.NETWORK, PluginErrorCode.TIMEOUT, PluginErrorCode.UNAVAILABLE)) {
                Result.retry()
            } else {
                Result.failure()
            }
        }
    }

    /**
     * Ends this run without waiting on the pause: a run is chained to start once [pause] ends, and
     * each playlist's checkpoint lets it continue where this run stopped.
     */
    private suspend fun resumeAfter(
        id: String,
        pause: ProviderPause,
        now: Long,
    ): Result {
        Log.i("PlaylistMirrorWorker", "Preparation paused by ${pause.pluginId}; resuming in ${(pause.untilMs - now) / 1000}s")
        WorkManager
            .getInstance(applicationContext)
            .enqueueUniqueWork(mirrorNowWorkName(id), ExistingWorkPolicy.APPEND_OR_REPLACE, mirrorNowRequest(inputData, id, pause.untilMs - now))
            .await()
        return Result.success()
    }
}
