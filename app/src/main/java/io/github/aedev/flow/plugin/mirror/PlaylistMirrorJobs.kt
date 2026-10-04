package io.github.aedev.flow.plugin.mirror

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistMirrorJobs
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: PlaylistMirrorStore,
        private val coordinator: PlaylistMirrorCoordinator,
        private val accounts: PluginAccounts,
        private val registry: PluginRegistry,
    ) {
        fun start(scope: CoroutineScope) {
            scope.launch {
                var previous = emptyMap<MirrorKey, List<Any?>>()
                combine(store.enabledPairs, accounts.accounts, registry.state) { pairs, accountStates, registryState ->
                    pairs
                        .mapNotNull { pair ->
                            val parts = pair.split('|')
                            if (parts.size != 2) return@mapNotNull null
                            coordinator.key(
                                parts[0],
                                parts[1],
                                nl.neerdael.milkbeat.catalog
                                    .EntityRef(nl.neerdael.milkbeat.catalog.EntityKind.PLAYLIST, "owned"),
                                registryState,
                                accountStates,
                            )
                        }.associateWith { key ->
                            listOf(
                                registryState.plugin(key.sourcePlugin),
                                registryState.plugin(key.targetPlugin),
                                accountStates[key.sourcePlugin],
                                accountStates[key.targetPlugin],
                            )
                        }
                }.distinctUntilChanged().collect { current ->
                    coordinator.cancelObsolete(current.keys)
                    val work = WorkManager.getInstance(context)
                    (previous.keys - current.keys).forEach { work.cancelAllWorkByTag("mirror:${it.id}") }
                    current.filter { (key, context) -> previous[key] != context }.forEach { (key, _) ->
                        val id = key.id
                        val input =
                            workDataOf(
                                "source" to key.sourcePlugin,
                                "sourceAccount" to key.sourceAccount,
                                "target" to key.targetPlugin,
                                "targetAccount" to key.targetAccount,
                            )
                        work.enqueueUniqueWork(
                            mirrorNowWorkName(id),
                            if (key in previous) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                            mirrorNowRequest(input, id),
                        )
                        work.enqueueUniquePeriodicWork(
                            "mirror-refresh:$id",
                            ExistingPeriodicWorkPolicy.KEEP,
                            PeriodicWorkRequestBuilder<PlaylistMirrorWorker>(6, TimeUnit.HOURS)
                                .setInitialDelay(6, TimeUnit.HOURS)
                                .setInputData(input)
                                .setConstraints(mirrorConstraints())
                                .addTag("mirror:$id")
                                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                                .build(),
                        )
                    }
                    previous = current
                }
            }
            scope.launch {
                var previous = emptyMap<String, InstalledPlugin>()
                combine(store.enabledPairs, registry.state) { pairs, registry ->
                    val mirrored = pairs.flatMap { it.split('|') }.toSet()
                    registry.plugins
                        .filter { it.enabled && (it.manifest.signIn.isNotEmpty() || it.id in mirrored) }
                        .associateBy { it.id }
                }.distinctUntilChanged()
                    .collect { current ->
                        current.filter { (id, plugin) -> previous[id] != plugin }.forEach { (id, _) ->
                            try {
                                accounts.refresh(id)
                            } catch (error: Exception) {
                                if (error is CancellationException) throw error
                            }
                        }
                        previous = current
                    }
            }
        }
    }

internal fun mirrorNowWorkName(id: String): String = "mirror-now:$id"

private fun mirrorConstraints(): Constraints =
    Constraints
        .Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(true)
        .build()

internal fun mirrorNowRequest(
    input: Data,
    id: String,
    delayMs: Long = 0L,
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<PlaylistMirrorWorker>()
        .setInputData(input)
        .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        .setConstraints(mirrorConstraints())
        .addTag("mirror:$id")
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()
