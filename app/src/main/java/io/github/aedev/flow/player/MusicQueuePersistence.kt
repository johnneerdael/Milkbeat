package io.github.aedev.flow.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.StateFlow

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.triggerQueueSave() {
    val currentQ = queueState.value
    if (currentQ.isNotEmpty()) {
        queuePersistence?.saveQueueDebounced(
            queue = currentQ,
            currentIndex = currentQueueIndexState.value,
            currentPosition = currentPositionState.value, // Use StateFlow for thread safety
            currentTrackId = currentTrackState.value?.videoId,
            shuffleEnabled = shuffleEnabledState.value,
            repeatMode =
                when (repeatModeState.value) {
                    RepeatMode.OFF -> 0
                    RepeatMode.ALL -> 1
                    RepeatMode.ONE -> 2
                },
            automix = automixState.value,
        )
    }
}

@OptIn(UnstableApi::class)
internal suspend fun EnhancedMusicPlayerManager.restoreSavedQueue() {
    try {
        val savedState = queuePersistence?.restoreQueue() ?: return

        if (savedState.queue.isEmpty()) return

        if ((player?.mediaItemCount ?: 0) > 0) return

        Log.d("EnhancedMusicPlayer", "Restoring saved queue: ${savedState.queue.size} tracks")

        queueState.value = savedState.queue
        currentQueueIndexState.value = savedState.currentIndex.coerceIn(0, savedState.queue.size - 1)
        shuffleEnabledState.value = savedState.shuffleEnabled
        repeatModeState.value =
            when (savedState.repeatMode) {
                1 -> RepeatMode.ALL
                2 -> RepeatMode.ONE
                else -> RepeatMode.OFF
            }
        automixState.value = savedState.automix

        val currentTrack =
            savedState.currentTrackId?.let { id ->
                savedState.queue.find { it.videoId == id }
            } ?: savedState.queue.getOrNull(savedState.currentIndex)

        currentTrack?.let {
            currentTrackState.value = it
            if (it.duration > 0) {
                playbackState.value = playbackState.value.copy(duration = it.duration * 1000L)
            }
        }
    } catch (e: Exception) {
        Log.e("EnhancedMusicPlayer", "Failed to restore queue", e)
    }
}
