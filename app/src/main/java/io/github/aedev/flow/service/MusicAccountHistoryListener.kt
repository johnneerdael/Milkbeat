package io.github.aedev.flow.service

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.aedev.flow.data.account.countsAsPlay
import io.github.aedev.flow.data.account.playThresholdMs
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

internal data class AccountListenProgress(
    val playedMs: Long,
    val durationMs: Long,
    val positionMs: Long,
    val progress: Boolean,
    val playbackSessionId: String,
)

/** Bounded history cadence on the session player, including its output-recovery holds. */
internal class MusicAccountHistoryListener(
    private val player: Player,
    private val scope: CoroutineScope,
    private val resolveTrack: (String) -> MusicTrack?,
    private val report: (MusicTrack, AccountListenProgress) -> Unit,
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) : Player.Listener {
    private class Listen(
        val mediaId: String,
        var track: MusicTrack?,
    ) {
        val playbackSessionId = UUID.randomUUID().toString()
        var playedMs = 0L
        var playingSinceMs: Long? = null
        var durationMs = 0L
        var positionMs = 0L
        var lastProgress: AccountListenProgress? = null
    }

    private var listen: Listen? = null
    private var cadence: Job? = null
    private var transitioned = false
    private var outgoingPosition: Player.PositionInfo? = null
    private var closed = false

    init {
        player.addListener(this)
        startCurrent()
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        transitioned = true
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (oldPosition.mediaItem?.mediaId == listen?.mediaId) outgoingPosition = oldPosition
    }

    // Transition callbacks expose the *new* current position. The discontinuity callback owns the
    // outgoing position; wait for the whole event batch so callback order cannot erase it.
    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (closed) return
        val previous = listen
        closeSegment()
        outgoingPosition?.let { previous?.positionMs = it.positionMs.coerceAtLeast(0) }
        if (transitioned || previous?.mediaId != player.currentMediaItem?.mediaId) {
            finish()
            startCurrent()
        } else {
            if (outgoingPosition != null) emit(progress = true)
            refreshCurrent()
            if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
                finish()
            } else {
                // Seek and pause boundaries are event-driven. Seek time never increases playedMs.
                if (outgoingPosition != null || !player.isPlaying) emit(progress = true)
                if (player.isPlaying) listen?.playingSinceMs = nowMs()
                updateCadence()
            }
        }
        transitioned = false
        outgoingPosition = null
    }

    fun close() {
        if (closed) return
        closed = true
        closeSegment()
        outgoingPosition?.let { listen?.positionMs = it.positionMs.coerceAtLeast(0) }
            ?: refreshCurrent()
        finish()
        player.removeListener(this)
    }

    private fun startCurrent() {
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        listen = Listen(mediaId, resolveTrack(mediaId))
        refreshCurrent()
        if (player.isPlaying) listen?.playingSinceMs = nowMs()
        updateCadence()
    }

    private fun refreshCurrent() {
        val current = listen ?: return
        if (current.mediaId != player.currentMediaItem?.mediaId) return
        current.positionMs = player.currentPosition.coerceAtLeast(0)
        if (current.track == null) current.track = resolveTrack(current.mediaId)
        current.durationMs = player.duration.takeIf { it > 0 }
            ?: current.track
                ?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1_000)
            ?: 0L
    }

    private fun closeSegment() {
        val current = listen ?: return
        current.playingSinceMs?.let { current.playedMs += (nowMs() - it).coerceAtLeast(0) }
        current.playingSinceMs = null
    }

    private fun playedMs(current: Listen): Long = current.playedMs + (current.playingSinceMs?.let { (nowMs() - it).coerceAtLeast(0) } ?: 0)

    private fun emit(progress: Boolean) {
        val current = listen ?: return
        val track = current.track ?: return
        val sample = AccountListenProgress(playedMs(current), current.durationMs, current.positionMs, progress, current.playbackSessionId)
        if (!countsAsPlay(sample.playedMs, sample.durationMs)) return
        if (progress && sample == current.lastProgress) return
        if (progress) current.lastProgress = sample
        report(track, sample)
    }

    private fun finish() {
        cadence?.cancel()
        cadence = null
        emit(progress = false)
        listen = null
    }

    private fun updateCadence() {
        if (listen?.playingSinceMs == null) {
            cadence?.cancel()
            cadence = null
            return
        }
        if (cadence?.isActive == true) return
        cadence =
            scope.launch {
                while (listen?.playingSinceMs != null && player.isPlaying) {
                    val current = listen ?: break
                    val untilQualified = playThresholdMs(current.durationMs) - playedMs(current)
                    delay(if (untilQualified > 0) minOf(REPORT_INTERVAL_MS, untilQualified) else REPORT_INTERVAL_MS)
                    if (!player.isPlaying || listen !== current) break
                    refreshCurrent()
                    emit(progress = true)
                }
            }
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 30_000L
    }
}
