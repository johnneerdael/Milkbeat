package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.mirror.MirrorPhase
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorState
import io.github.aedev.flow.ui.tv.components.TvButton

@Composable
internal fun TvPlaylistMirrorStatus(
    state: PlaylistMirrorState,
    retry: () -> Unit,
) {
    if (!state.isPreparing && !state.ready && state.error == null) return
    Column {
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
