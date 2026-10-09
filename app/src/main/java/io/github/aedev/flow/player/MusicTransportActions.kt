package io.github.aedev.flow.player

import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.player.diagnostics.TraceEvent
import io.github.aedev.flow.player.diagnostics.TraceField
import kotlinx.coroutines.launch

internal fun EnhancedMusicPlayerManager.performSeekTo(position: Long) {
    PlaybackTrace.event(TraceEvent.SEEK, TraceField.POSITION_MS to position)
    scope.launch {
        val duration = player?.duration?.takeIf { it > 0 } ?: playbackState.value.duration.takeIf { it > 0 }
        val target = duration?.let { position.coerceIn(0L, it) } ?: position.coerceAtLeast(0L)
        currentPositionState.value = target
        playbackState.value = playbackState.value.copy(position = target)
        player?.seekTo(target)
    }
}

internal fun EnhancedMusicPlayerManager.performPlay() {
    PlaybackTrace.event(TraceEvent.PLAY)
    scope.launch { player?.play() }
}

internal fun EnhancedMusicPlayerManager.performPause() {
    PlaybackTrace.event(TraceEvent.PAUSE)
    scope.launch { player?.pause() }
}

internal fun EnhancedMusicPlayerManager.performStop() {
    PlaybackTrace.event(TraceEvent.STOP)
    scope.launch {
        player?.stop()
        playbackState.value =
            playbackState.value.copy(isPlaying = false, isBuffering = false, isPreparing = false, position = 0L)
        currentPositionState.value = 0L
    }
}
