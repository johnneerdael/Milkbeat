package io.github.aedev.flow.ui.tv.music

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.playback.MusicResolutionStage
import io.github.aedev.flow.plugin.playback.MusicResolutionStatus

@Composable
internal fun musicResolutionStatusText(
    status: MusicResolutionStatus?,
    playbackId: String?,
): String? {
    val current = status?.takeIf { it.playbackId == playbackId } ?: return null
    val resource =
        when (current.stage) {
            MusicResolutionStage.MATCHING -> {
                R.string.music_matching_provider
            }

            MusicResolutionStage.SAVED_MATCH -> {
                R.string.music_loading_saved_match
            }

            MusicResolutionStage.LOADING_STREAM -> {
                if (current.usedSavedMatch) R.string.music_loading_saved_match else R.string.music_loading_provider
            }
        }
    return stringResource(resource, current.providerName)
}
