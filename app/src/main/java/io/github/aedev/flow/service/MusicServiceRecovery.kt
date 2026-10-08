package io.github.aedev.flow.service

import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import io.github.aedev.flow.R
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicPlaybackRecoveryPlanner
import io.github.aedev.flow.player.MusicQueuePlanner
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.plugin.playback.isPictureUnavailable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.min

private const val TAG = "Media3MusicService"
private const val MAX_RETRY_PER_SONG = 5
private const val BASE_RETRY_DELAY_MS = 3000L
private const val MAX_RETRY_DELAY_MS = 30000L
private const val FAILED_SONGS_CACHE_SIZE = 50
private const val MUSIC_URI_SCHEME = "music"

/**
 * Main error handling logic with error-type-specific handlers.
 */
internal fun Media3MusicService.handlePlayerError(
    error: PlaybackException,
    errorWindowIndex: Int,
) {
    val failed =
        MusicPlaybackRecoveryPlanner.resolveFailedItem(
            errorWindowIndex = errorWindowIndex,
            currentIndex = player.currentMediaItemIndex,
            currentPositionMs = player.currentPosition,
            mediaIds = playerMediaIds(),
        )
    if (failed == null) {
        Log.e(TAG, "Player error with no resolvable media item", error)
        return
    }
    val mediaId = failed.mediaId

    if (io.github.aedev.flow.plugin.playback
            .playbackSessionLost(error)
    ) {
        retryJobCancel()
        player.stop()
        notifyMusicWarning(getString(R.string.music_playback_warning_generic))
        return
    }
    if (error.isPictureUnavailable()) {
        if (fallBackToSong(failed)) Log.w(TAG, "Picture unavailable; continuing the accepted song")
        return
    }
    io.github.aedev.flow.player.error.serverAbrFailureOf(error)?.let { failure ->
        handleServerAbrFailure(failed, failure)
        return
    }

    val accepted = pluginAudio.current(mediaId)
    if (accepted?.stream?.serverAbr != null && error.errorCode in
        setOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_TIMEOUT,
        )
    ) {
        val http =
            io.github.aedev.flow.player.error.StreamHttpFailure
                .of(error)
        handleServerAbrFailure(
            failed,
            nl.neerdael.milkbeat.plugin
                .StreamFailure(http?.first ?: accepted.stream.url, http?.second),
        )
        return
    }

    if (fallBackToSong(failed)) {
        Log.w(TAG, "Music video of $mediaId failed (${error.errorCodeName}), playing its song instead", error)
        return
    }

    Log.e(TAG, "Playback error for $mediaId: ${error.errorCodeName} (code=${error.errorCode})", error)
    lastPlaybackErrorAtMap[mediaId] = System.currentTimeMillis()

    if (recentlyFailedSongs.contains(mediaId)) {
        Log.w(TAG, "$mediaId is in recently failed list, skipping to next")
        skipPastFailedItem(failed)
        return
    }

    val currentRetry = retryCountMap.getOrDefault(mediaId, 0)

    if (currentRetry >= MAX_RETRY_PER_SONG) {
        handleFinalFailure(failed)
        return
    }

    performAggressiveCacheClear(mediaId)

    when {
        isAudioRendererError(error) -> {
            Log.d(TAG, "AudioTrack error detected (${error.errorCode}), performing safe recovery")
            handleAudioRendererError(failed, currentRetry)
        }

        isRangeNotSatisfiableError(error) -> {
            Log.d(TAG, "Range Not Satisfiable (416) detected, performing strict recovery")
            handleRangeNotSatisfiableError(failed, currentRetry)
        }

        isPageReloadError(error) -> {
            Log.d(TAG, "Page reload error detected, performing strict recovery")
            handlePageReloadError(failed, currentRetry)
        }

        isExpiredUrlError(error) -> {
            Log.d(TAG, "Expired URL (403) detected, refreshing stream URL")
            notifyMusicWarning(getString(R.string.music_playback_warning_forbidden))
            handleExpiredUrlError(failed, currentRetry)
        }

        isFileNotFoundError(error) -> {
            Log.d(TAG, "Cache file missing (ENOENT) detected, refreshing stream")
            handleFileNotFoundError(failed, currentRetry)
        }

        !connectivityObserver.checkCurrentConnectivity() || isNetworkError(error) -> {
            Log.d(TAG, "Network-related error detected, waiting for connection")
            notifyMusicWarning(getString(R.string.music_playback_warning_network))
            handleNetworkError(failed, currentRetry)
        }

        else -> {
            Log.d(TAG, "Generic/IO error detected (${error.errorCode}), attempting recovery")
            handleGenericError(failed, currentRetry)
        }
    }
}

/** Refresh the same accepted source/account after a native protocol instruction, bounded by the
 * existing per-item retry counter. Releasing the old source cancels its outstanding POST loader. */
internal fun Media3MusicService.handleServerAbrFailure(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    failure: nl.neerdael.milkbeat.plugin.StreamFailure,
) {
    val attempt = retryCountMap.getOrDefault(failed.mediaId, 0)
    if (attempt >= MAX_RETRY_PER_SONG || recentlyFailedSongs.contains(failed.mediaId)) {
        handleFinalFailure(failed)
        return
    }
    retryCountMap[failed.mediaId] = attempt + 1
    retryJobCancel()
    // Keep the accepted recording before expiring the URL cache; no matching/provider switch.
    pluginAudio.failed(failed.mediaId, failure.url, failure.status, failure.reloadPlaybackContext, failure.serverAbrFailure)
    pendingRetryJob =
        lifecycleScope.launch {
            delay(BASE_RETRY_DELAY_MS)
            val index = playerIndexOf(failed)
            if (index == MusicQueuePlanner.INDEX_UNSET) return@launch
            downloadUtil.invalidateUrlCache(failed.mediaId)
            if (player.currentMediaItem?.mediaId == failed.mediaId) {
                val playing = player.playWhenReady
                player.stop()
                if (refreshStreamMediaItem(failed, preservePicture = true)) {
                    player.prepare()
                    player.playWhenReady = playing
                }
            } else {
                refreshStreamMediaItemAt(index, failed.mediaId, failed.resumePositionMs, preservePicture = true)
            }
        }
}

/**
 * A music video whose picture or sound fails plays on as its song, at the same moment, before any
 * retry counts against the track. Returns false for anything that is not a music video.
 */
internal fun Media3MusicService.fallBackToSong(failed: MusicPlaybackRecoveryPlanner.FailedItem): Boolean {
    val index = playerIndexOf(failed)
    if (index == MusicQueuePlanner.INDEX_UNSET) return false
    if (player
            .getMediaItemAt(index)
            .localConfiguration
            ?.uri
            ?.scheme != MusicVideoItems.SCHEME
    ) {
        return false
    }
    // Either half may have failed; both urls are fetched afresh, and the track stays a song.
    downloadUtil.invalidateUrlCache(failed.mediaId)
    io.github.aedev.flow.player.EnhancedMusicPlayerManager
        .onVideoUnavailable(failed.mediaId)
    if (!refreshStreamMediaItemAt(index, failed.mediaId, failed.resumePositionMs)) return false
    player.prepare()
    player.play()
    return true
}

internal fun Media3MusicService.playerMediaIds(): List<String> = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId }

/**
 * Where the failed item sits in the playlist *now*. Recovery runs after a delay, and the radio
 * appends and queue edits in between move it — an index captured at error time goes stale.
 */
internal fun Media3MusicService.playerIndexOf(failed: MusicPlaybackRecoveryPlanner.FailedItem): Int =
    MusicQueuePlanner.currentQueueIndex(
        queueIds = playerMediaIds(),
        playerIndex = failed.index,
        currentTrackId = failed.mediaId,
    )

/** Re-prepares the item that actually failed, never whatever happens to be current. */
internal fun Media3MusicService.restartFailedItem(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    fromStart: Boolean = false,
) {
    val index = playerIndexOf(failed)
    if (index == MusicQueuePlanner.INDEX_UNSET) return
    player.seekTo(index, if (fromStart) 0L else failed.resumePositionMs)
    player.prepare()
    player.play()
}

internal fun Media3MusicService.skipPastFailedItem(failed: MusicPlaybackRecoveryPlanner.FailedItem) {
    val index = playerIndexOf(failed)
    when {
        // Shuffle order only matters from where we stand, so hand the skip to the player.
        index == player.currentMediaItemIndex && player.hasNextMediaItem() -> {
            player.seekToNextMediaItem()
            player.prepare()
            player.play()
        }

        index != MusicQueuePlanner.INDEX_UNSET && index + 1 < player.mediaItemCount -> {
            player.seekTo(index + 1, 0L)
            player.prepare()
            player.play()
        }

        player.repeatMode == Player.REPEAT_MODE_ALL && player.mediaItemCount > 0 -> {
            player.seekTo(0, 0L)
            player.prepare()
            player.play()
        }
    }
}

internal fun Media3MusicService.performAggressiveCacheClear(mediaId: String) {
    Log.d(TAG, "Performing aggressive cache clear for $mediaId")
    try {
        downloadUtil.performAggressiveCacheClear(mediaId)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to clear download cache for $mediaId", e)
    }
}

internal fun Media3MusicService.getHttpResponseCode(error: PlaybackException): Int? {
    var cause: Throwable? = error.cause
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) {
            return cause.responseCode
        }
        cause = cause.cause
    }
    return null
}

internal fun Media3MusicService.isExpiredUrlError(error: PlaybackException): Boolean = getHttpResponseCode(error) == 403

internal fun Media3MusicService.isRangeNotSatisfiableError(error: PlaybackException): Boolean = getHttpResponseCode(error) == 416

internal fun Media3MusicService.isPageReloadError(error: PlaybackException): Boolean {
    val errorMessage = error.message?.lowercase(Locale.ROOT) ?: ""
    val causeMessage = error.cause?.message?.lowercase(Locale.ROOT) ?: ""
    val reloadKeywords = listOf("page needs to be reloaded", "page must be reloaded", "reload")
    return reloadKeywords.any { errorMessage.contains(it) || causeMessage.contains(it) }
}

internal fun Media3MusicService.isFileNotFoundError(error: PlaybackException): Boolean =
    error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
        (error.cause as? PlaybackException)?.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND

internal fun Media3MusicService.isAudioRendererError(error: PlaybackException): Boolean =
    error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

internal fun Media3MusicService.isNetworkError(error: PlaybackException): Boolean =
    error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ||
        error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT

internal fun Media3MusicService.handleAudioRendererError(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    currentRetry: Int,
) {
    retryCountMap[failed.mediaId] = currentRetry + 1
    retryJobCancel()
    pendingRetryJob =
        lifecycleScope.launch {
            try {
                player.pause()
                delay(BASE_RETRY_DELAY_MS * 3)
                restartFailedItem(failed)
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack recovery failed", e)
                handleFinalFailure(failed)
            }
        }
}

internal fun Media3MusicService.handleRangeNotSatisfiableError(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    currentRetry: Int,
) {
    retryCountMap[failed.mediaId] = currentRetry + 1
    retryJobCancel()
    pendingRetryJob =
        lifecycleScope.launch {
            delay(BASE_RETRY_DELAY_MS)
            try {
                restartFailedItem(failed, fromStart = true)
            } catch (e: Exception) {
                Log.e(TAG, "Range retry failed", e)
            }
        }
}

internal fun Media3MusicService.handlePageReloadError(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    currentRetry: Int,
) {
    retryCountMap[failed.mediaId] = currentRetry + 1
    retryJobCancel()
    pendingRetryJob =
        lifecycleScope.launch {
            delay(BASE_RETRY_DELAY_MS * 2)
            try {
                restartFailedItem(failed)
            } catch (e: Exception) {
                Log.e(TAG, "Page reload recovery failed", e)
            }
        }
}

internal fun Media3MusicService.handleExpiredUrlError(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    currentRetry: Int,
) {
    val mediaId = failed.mediaId
    retryCountMap[mediaId] = currentRetry + 1
    retryJobCancel()
    pendingRetryJob =
        lifecycleScope.launch {
            delay(BASE_RETRY_DELAY_MS)
            try {
                // Tell the plugin which stream was refused before the cache forgets it.
                pluginAudio.current(mediaId)?.let { pluginAudio.failed(mediaId, it.stream.url, status = 403) }
                downloadUtil.invalidateUrlCache(mediaId)
                player.stop()
                if (refreshStreamMediaItem(failed)) {
                    player.prepare()
                    player.play()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Expired URL recovery failed", e)
            }
        }
}

/**
 * Rebuilds a streaming item so the next prepare resolves a fresh url. Returns false when the
 * item is gone or plays from a local file, which has no url to refresh — rewriting one would
 * silently turn offline playback into a stream.
 */
internal fun Media3MusicService.refreshStreamMediaItem(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    preservePicture: Boolean = false,
): Boolean {
    val index = playerIndexOf(failed)
    if (index == MusicQueuePlanner.INDEX_UNSET) return false
    return refreshStreamMediaItemAt(index, failed.mediaId, failed.resumePositionMs, preservePicture)
}

internal fun Media3MusicService.refreshStreamMediaItemAt(
    index: Int,
    mediaId: String,
    positionMs: Long,
    preservePicture: Boolean = false,
): Boolean {
    val currentItem = player.getMediaItemAt(index)
    val uri = currentItem.localConfiguration?.uri ?: return false
    if (uri.scheme != MUSIC_URI_SCHEME && uri.scheme != MusicVideoItems.SCHEME) return false

    val refreshedItem =
        currentItem
            .buildUpon()
            .setUri(if (preservePicture) uri else MusicVideoItems.songUri(uri, mediaId))
            .setMediaId(mediaId)
            .setCustomCacheKey(mediaId)
            .build()

    player.replaceMediaItem(index, refreshedItem)
    player.seekTo(index, positionMs)
    return true
}

internal fun Media3MusicService.handleFileNotFoundError(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    currentRetry: Int,
) {
    retryCountMap[failed.mediaId] = currentRetry + 1
    retryJobCancel()
    pendingRetryJob =
        lifecycleScope.launch {
            delay(BASE_RETRY_DELAY_MS)
            try {
                restartFailedItem(failed)
            } catch (e: Exception) {
                Log.e(TAG, "File not found recovery failed", e)
            }
        }
}

internal fun Media3MusicService.handleNetworkError(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    currentRetry: Int,
) {
    pendingNetworkRetry = failed
    if (!connectivityObserver.checkCurrentConnectivity()) {
        Log.d(TAG, "No network connectivity, waiting for connection...")
        waitingForNetwork = true
        retryCountMap[failed.mediaId] = currentRetry + 1
    } else {
        scheduleRetry(failed, currentRetry, delayMultiplier = 2.0)
    }
}

internal fun Media3MusicService.handleGenericError(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    currentRetry: Int,
) {
    scheduleRetry(failed, currentRetry, delayMultiplier = 1.5)
}

internal fun Media3MusicService.retryJobCancel() {
    pendingRetryJob?.cancel()
    pendingRetryJob = null
}

internal fun Media3MusicService.scheduleRetry(
    failed: MusicPlaybackRecoveryPlanner.FailedItem,
    currentRetry: Int,
    delayMultiplier: Double,
) {
    val mediaId = failed.mediaId
    retryCountMap[mediaId] = currentRetry + 1
    val baseDelay = (BASE_RETRY_DELAY_MS * delayMultiplier).toLong()
    val delay = min(baseDelay * (1L shl currentRetry), MAX_RETRY_DELAY_MS)

    Log.d(TAG, "Scheduling retry ${currentRetry + 1}/$MAX_RETRY_PER_SONG for $mediaId in ${delay}ms")
    retryJobCancel()
    pendingRetryJob =
        lifecycleScope.launch {
            delay(delay)
            try {
                restartFailedItem(failed)
            } catch (e: Exception) {
                Log.e(TAG, "Scheduled retry failed for $mediaId", e)
            }
        }
}

internal fun Media3MusicService.handleFinalFailure(failed: MusicPlaybackRecoveryPlanner.FailedItem) {
    val mediaId = failed.mediaId
    Log.w(TAG, "All retries exhausted for $mediaId, marking as failed")
    notifyMusicWarning(getString(R.string.music_playback_warning_final))
    retryCountMap.remove(mediaId)
    lastPlaybackErrorAtMap.remove(mediaId)
    if (recentlyFailedSongs.size >= FAILED_SONGS_CACHE_SIZE) {
        recentlyFailedSongs.iterator().next().let { recentlyFailedSongs.remove(it) }
    }
    recentlyFailedSongs.add(mediaId)
    skipPastFailedItem(failed)
}

internal fun Media3MusicService.notifyMusicWarning(message: String) {
    io.github.aedev.flow.player.EnhancedMusicPlayerManager
        .showPlaybackWarning(message)
}

internal fun Media3MusicService.stopPlaybackAndService() {
    retryJobCancel()
    waitingForNetwork = false
    if (playerInitialized) {
        player.pause()
        player.stop()
        player.clearMediaItems()
    }
    io.github.aedev.flow.player.EnhancedMusicPlayerManager
        .clearCurrentTrack()
    releaseLocks()
    stopSelf()
}

internal fun Media3MusicService.triggerRetryAfterNetworkRestore() {
    val failed = pendingNetworkRetry ?: return
    val currentRetry = retryCountMap.getOrDefault(failed.mediaId, 0)

    if (currentRetry < MAX_RETRY_PER_SONG) {
        Log.d(TAG, "Triggering retry after network restore for ${failed.mediaId}")
        performAggressiveCacheClear(failed.mediaId)

        lifecycleScope.launch {
            delay(1000)
            try {
                restartFailedItem(failed)
            } catch (e: Exception) {
                Log.e(TAG, "Network restore retry failed for ${failed.mediaId}", e)
            }
        }
    }
    pendingNetworkRetry = null
}
