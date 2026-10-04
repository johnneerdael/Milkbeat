package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.preload.PRELOAD_ERROR
import io.github.aedev.flow.plugin.preload.PlaylistPreloadFailure
import io.github.aedev.flow.plugin.preload.PlaylistPreloadJobs
import io.github.aedev.flow.plugin.preload.preloadProgress
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.utils.formatClockTime

@Composable
internal fun TvPlaylistPreloadItem(
    plugin: InstalledPlugin,
    jobs: PlaylistPreloadJobs,
) {
    val updates = remember(jobs, plugin.id) { jobs.observe(plugin.id) }
    val work by updates.collectAsStateWithLifecycle(initialValue = null)
    val pauses = remember(jobs, plugin.id) { jobs.observePause(plugin.id) }
    val pause by pauses.collectAsStateWithLifecycle(initialValue = null)
    val info = work
    val progress = (if (info?.state == WorkInfo.State.SUCCEEDED) info.outputData else info?.progress)?.preloadProgress()
    val active = info?.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TvSectionHeader(stringResource(R.string.playlist_preload_title, plugin.manifest.name))
        Text(
            stringResource(R.string.playlist_preload_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val status =
            when (info?.state) {
                WorkInfo.State.SUCCEEDED -> {
                    stringResource(
                        R.string.playlist_preload_completed,
                        progress?.matched ?: 0,
                        progress?.unavailable ?: 0,
                    )
                }

                WorkInfo.State.RUNNING -> {
                    stringResource(
                        R.string.playlist_preload_running,
                        progress?.indexed ?: 0,
                        progress?.matched ?: 0,
                        progress?.unavailable ?: 0,
                    )
                }

                WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> {
                    pause?.let { stringResource(R.string.background_provider_paused, it.providerName, formatClockTime(LocalContext.current, it.untilMs)) }
                        ?: stringResource(R.string.playlist_preload_queued)
                }

                WorkInfo.State.CANCELLED -> {
                    stringResource(R.string.playlist_preload_cancelled)
                }

                WorkInfo.State.FAILED -> {
                    stringResource(preloadFailureResource(info.outputData.getString(PRELOAD_ERROR)))
                }

                null -> {
                    null
                }
            }
        if (status != null) Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TvButton(
            stringResource(if (active) R.string.playlist_preload_cancel else R.string.playlist_preload_start),
            { if (active) jobs.cancel(plugin.id) else jobs.start(plugin.id) },
        )
    }
}

private fun preloadFailureResource(reason: String?): Int =
    when (reason) {
        PlaylistPreloadFailure.ACCOUNT_CHANGED.name -> R.string.playlist_preload_account_changed
        PlaylistPreloadFailure.PROVIDERS_CHANGED.name -> R.string.playlist_preload_providers_changed
        PlaylistPreloadFailure.PLUGIN_CHANGED.name -> R.string.playlist_preload_plugin_changed
        PlaylistPreloadFailure.NO_AUDIO_PROVIDER.name -> R.string.playlist_preload_no_audio
        PlaylistPreloadFailure.INVALID_PAGINATION.name -> R.string.playlist_preload_invalid_pagination
        else -> R.string.playlist_preload_failed
    }
