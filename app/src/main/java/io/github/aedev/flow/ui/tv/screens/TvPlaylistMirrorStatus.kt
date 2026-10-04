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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.mirror.MirrorPhase
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorState
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

@Composable
internal fun TvPlaylistMirrorStatus(
    state: PlaylistMirrorState,
    retry: () -> Unit,
) {
    if (!state.isPreparing && !state.ready && state.error == null) return
    Column {
        if (state.ready && state.error == null) {
            TvYouTubeCopyStatus(stringResource(R.string.playlist_mirror_ready, state.matched, state.missing))
            return@Column
        }
        Text(
            text =
                when {
                    state.error != null -> {
                        stringResource(R.string.playlist_mirror_failed)
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
                },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.isPreparing && state.error == null && !state.ready) {
            LinearProgressIndicator(progress = { state.percentage / 100f }, modifier = Modifier.fillMaxWidth())
        }
        if (state.error != null) TvButton(text = stringResource(R.string.playlist_mirror_retry), onClick = retry)
    }
}

/** The playlist plays from its private YouTube Music copy: the YouTube mark beside the ready line. */
@Composable
private fun TvYouTubeCopyStatus(text: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_youtube_mono),
            contentDescription = stringResource(R.string.playlist_mirror_youtube_source),
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(LocalTvDimens.current.sourceBadgeSize),
        )
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
