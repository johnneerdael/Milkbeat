package io.github.aedev.flow.service

import android.util.Log
import androidx.lifecycle.lifecycleScope
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager

private const val TAG = "Media3MusicService"

internal fun Media3MusicService.closePlayingSegment() {
    if (learnPlayingSinceMs >= 0) {
        learnPlayedMs += android.os.SystemClock.elapsedRealtime() - learnPlayingSinceMs
        learnPlayingSinceMs = -1L
    }
}

// Queue metadata often ships duration=0 (related/next payloads omit it), so the
// player's own duration — valid once READY — is the reliable denominator.
internal fun Media3MusicService.refreshLearnDuration() {
    if (!playerInitialized) return
    if (player.currentMediaItem?.mediaId != learnMediaId) return
    val d = player.duration
    if (d > 0) learnDurationMs = d
}

internal fun Media3MusicService.resolveLearnTrack(mediaId: String?): MusicTrack? {
    if (mediaId.isNullOrBlank()) return null
    val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
    return manager.queue.value.firstOrNull { it.videoId == mediaId }
        ?: manager.currentTrack.value?.takeIf { it.videoId == mediaId }
        ?: manager.automixItems.value.firstOrNull { it.videoId == mediaId }
}

internal fun Media3MusicService.startListenSession(mediaId: String?) {
    learnMediaId = mediaId
    // Pin the track now: by finalize time a new playlist may have replaced the
    // queue and the outgoing track would no longer resolve.
    learnTrack = resolveLearnTrack(mediaId)
    // Pin the genre context too — it belongs to the queue this track started in.
    learnGenre =
        io.github.aedev.flow.player.EnhancedMusicPlayerManager
            .playContextGenre
    learnDurationMs = 0L
    learnPlayedMs = 0L
    learnPlayingSinceMs =
        if (playerInitialized && player.isPlaying) android.os.SystemClock.elapsedRealtime() else -1L
    refreshLearnDuration()
}

internal fun Media3MusicService.finalizeListenSession() {
    closePlayingSegment()
    val mediaId = learnMediaId
    val pinnedTrack = learnTrack
    val pinnedDurationMs = learnDurationMs
    val playedMs = learnPlayedMs
    val pinnedGenre = learnGenre
    learnMediaId = null
    learnTrack = null
    learnGenre = null
    learnDurationMs = 0L
    learnPlayedMs = 0L
    if (mediaId.isNullOrBlank() || playedMs <= 0) {
        Log.d(TAG, "listen finalize skipped: id=$mediaId playedMs=$playedMs")
        return
    }

    val track = pinnedTrack?.takeIf { it.videoId == mediaId } ?: resolveLearnTrack(mediaId)
    if (track == null) {
        Log.w(TAG, "listen finalize: no track match for $mediaId")
        return
    }
    val durationMs = if (track.duration > 0) track.duration.toLong() * 1000 else pinnedDurationMs
    if (durationMs <= 0) {
        Log.w(TAG, "listen finalize: no duration for $mediaId")
        return
    }

    Log.d(TAG, "listen finalize: $mediaId playedMs=$playedMs pct=${playedMs.toDouble() / durationMs}")
    // Engine-scoped, NOT lifecycleScope: the finalize from onDestroy runs after
    // this service's scope is already cancelled, and the session must still land.
    musicBrain.onListenSessionAsync(track, playedMs.toDouble() / durationMs, pinnedGenre, playedMs)
    accountPlayHistory.onListened(track, playedMs, durationMs)
}
