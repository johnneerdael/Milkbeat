package io.github.aedev.flow.service

import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import io.github.aedev.flow.player.MusicPlaybackRecoveryPlanner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "Media3MusicService"

internal fun Media3MusicService.isAudioRendererError(error: PlaybackException): Boolean =
    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

internal fun Media3MusicService.handleAudioRendererError(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    currentRetry: Int,
) {
    retryCountMap[failed.mediaId] = currentRetry + 1
    retryJobCancel()
    pendingRetryJob =
        lifecycleScope.launch {
            try {
                // Release the broken renderer without using pause as a recovery flag.
                accountHistoryListener?.sourceWillReopen(failed.mediaId)
                player.stop()
                delay(BASE_RETRY_DELAY_MS * 3)
                restartFailedItem(failed)
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack recovery failed", e)
                handleFinalFailure(failed)
            }
        }
}

/**
 * A media open that reached Ready without ever producing audio output — no selected audio track, or
 * a selected track that never advanced — is rejected before playback counts as started. The same
 * queue occurrence is retried at the same position with fresh media: the accepted provider resolves
 * a fresh URL first, and the normal configured order takes over if it cannot.
 */
internal fun Media3MusicService.failOverSilentStart(
    mediaId: String,
    positionMs: Long,
) {
    val index = player.currentMediaItemIndex
    if (player.currentMediaItem?.mediaId != mediaId || index !in 0 until player.mediaItemCount) return
    val failed = MusicPlaybackRecoveryPlanner.FailedItem(index, mediaId, positionMs)
    val retryCount = retryCountMap.getOrDefault(mediaId, 0)
    if (retryCount >= MAX_RETRY_PER_SONG || mediaId in recentlyFailedSongs) {
        handleFinalFailure(failed)
        return
    }
    Log.w(TAG, "Rejecting a silent start for $mediaId at ${positionMs}ms; recovering with fresh media")
    lastPlaybackErrorAtMap[mediaId] = System.currentTimeMillis()
    retryCountMap[mediaId] = retryCount + 1
    retryJobCancel()
    pluginAudio.current(mediaId)?.let { pluginAudio.failed(mediaId, it.stream.url, null) }
    downloadUtil.invalidateUrlCache(mediaId)
    if (refreshStreamMediaItemAt(index, mediaId, positionMs, preservePicture = true)) {
        player.prepare()
    } else {
        handleFinalFailure(failed)
    }
}
