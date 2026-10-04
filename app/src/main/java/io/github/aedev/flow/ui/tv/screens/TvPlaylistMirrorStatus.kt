package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.mirror.MirrorPhase
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorState
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import io.github.aedev.flow.utils.formatClockTime
import nl.neerdael.milkbeat.plugin.PluginManifest

@Composable
internal fun TvPlaylistMirrorStatus(
    state: PlaylistMirrorState,
    target: PluginManifest?,
    retry: () -> Unit,
) {
    if (!state.isPreparing && !state.ready && state.error == null && state.paused == null) return
    Column {
        val text =
            when {
                state.error != null -> {
                    stringResource(R.string.playlist_mirror_failed)
                }

                state.paused != null -> {
                    stringResource(
                        R.string.background_provider_paused,
                        state.paused.providerName,
                        formatClockTime(LocalContext.current, state.paused.untilMs),
                    )
                }

                state.ready -> {
                    stringResource(R.string.playlist_mirror_ready, state.matched, state.missing)
                }

                state.phase == MirrorPhase.SOURCE_LOADING -> {
                    stringResource(R.string.playlist_mirror_starting, state.percentage)
                }

                state.phase == MirrorPhase.WRITING -> {
                    stringResource(R.string.playlist_mirror_writing, state.percentage, state.missing)
                }

                state.phase == MirrorPhase.VERIFYING -> {
                    stringResource(
                        R.string.playlist_mirror_verifying,
                        state.percentage,
                        state.missing,
                    )
                }

                else -> {
                    stringResource(
                        R.string.playlist_mirror_progress_percent,
                        state.percentage,
                        state.matched,
                        state.total,
                        state.missing,
                    )
                }
            }
        if (state.ready && state.error == null && target != null && playsFromYouTube(target)) {
            TvYouTubeCopyStatus(text, target.name)
        } else {
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (state.isPreparing && state.error == null && !state.ready) {
            LinearProgressIndicator(progress = { state.percentage / 100f }, modifier = Modifier.fillMaxWidth())
        }
        if (state.error != null) TvButton(text = stringResource(R.string.playlist_mirror_retry), onClick = retry)
    }
}

// The YouTube mark only stands for a copy kept where YouTube video ids are the identity; another
// import-capable provider gets the plain ready line.
internal fun playsFromYouTube(target: PluginManifest): Boolean = target.roles.metadata?.idSpace == YOUTUBE_MUSIC_ID_SPACE

private const val YOUTUBE_MUSIC_ID_SPACE = "ytm"

@Composable
private fun TvYouTubeCopyStatus(
    text: String,
    targetName: String,
) {
    // Top-aligned with room to wrap: the cover pane is narrow and the counts vary, so the line may take two rows.
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_youtube_mono),
            contentDescription = stringResource(R.string.playlist_mirror_copy_source, targetName),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(LocalTvDimens.current.sourceBadgeSize),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}
