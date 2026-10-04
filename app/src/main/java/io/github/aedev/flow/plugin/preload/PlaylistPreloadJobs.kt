package io.github.aedev.flow.plugin.preload

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.plugin.background.BackgroundProviderBackoff
import io.github.aedev.flow.plugin.background.PausedProvider
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import nl.neerdael.milkbeat.catalog.ProviderAccount
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class PlaylistPreloadJobs
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
        private val backoff: BackgroundProviderBackoff,
    ) {
        private val work: WorkManager get() = WorkManager.getInstance(context)

        fun observe(pluginId: String): Flow<WorkInfo?> = work.getWorkInfosForUniqueWorkFlow(preloadWorkName(pluginId)).map(::currentPreload)

        /** The pause holding back [pluginId]'s indexing, by it or one of the selected audio providers. */
        fun observePause(pluginId: String): Flow<PausedProvider?> =
            combine(backoff.pauses, registry.state) { pauses, state ->
                val now = backoff.now()
                (listOf(pluginId) + state.selection.audio)
                    .mapNotNull { pauses[it]?.takeIf { pause -> pause.untilMs > now } }
                    .maxByOrNull { it.untilMs }
                    ?.let { PausedProvider(state.plugin(it.pluginId)?.manifest?.name ?: it.pluginId, it.untilMs) }
            }

        fun start(pluginId: String) {
            val account = accounts.accounts.value[pluginId] as? ProviderAccount.SignedIn ?: return
            val input =
                workDataOf(
                    PRELOAD_PLUGIN to pluginId,
                    PRELOAD_ACCOUNT to account.key,
                    PRELOAD_AUDIO to
                        registry.state.value.selection.audio
                            .toTypedArray(),
                )
            work.enqueueUniqueWork(preloadWorkName(pluginId), ExistingWorkPolicy.KEEP, preloadRequest(input))
        }

        fun cancel(pluginId: String) {
            work.cancelUniqueWork(preloadWorkName(pluginId))
        }
    }

internal fun preloadRequest(
    input: Data,
    delayMs: Long = 0L,
): OneTimeWorkRequest =
    OneTimeWorkRequestBuilder<PlaylistPreloadWorker>()
        .setInputData(input)
        .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        .setConstraints(
            Constraints
                .Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build(),
        ).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

/**
 * The run to show for one indexing job. A paused run is chained to the run that continues it, so
 * the unique work holds both: the pending continuation wins, and a paused run is shown only when
 * nothing else is left.
 */
internal fun currentPreload(runs: List<WorkInfo>): WorkInfo? =
    runs.firstOrNull { !it.state.isFinished }
        ?: runs.lastOrNull { !it.outputData.keyValueMap.containsKey(PRELOAD_PAUSED_UNTIL) }
        ?: runs.lastOrNull()
