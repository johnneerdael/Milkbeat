package io.github.aedev.flow.service

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.aedev.flow.data.account.AccountPlayHistory
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
    /** Plugin API 12 lifecycle: why the listen ended, when [progress] is false. */
    val endReason: String? = null,
    /** Plugin API 12 lifecycle: listening time measured from real position deltas, seek-free. */
    val playingMs: Long = 0L,
    /** Plugin API 12 lifecycle: the listen began from a listener action rather than an advance. */
    val manual: Boolean = false,
)

/**
 * Bounded history cadence on the session player, including its output-recovery holds. A provider
 * that opted into API 12 lifecycle reporting is sampled every second and checkpointed every five,
 * with playing time measured from position deltas so seeks never count; other providers keep the
 * plain thirty-second cumulative cadence.
 */
internal class MusicAccountHistoryListener(
    private val player: Player,
    private val scope: CoroutineScope,
    private val resolveTrack: (String) -> MusicTrack?,
    private val report: (MusicTrack, AccountListenProgress) -> Unit,
    private val lifecycleReporter: (String) -> Boolean = { false },
    /** Whether the open has actually produced audio; a silent open accrues no listening time. */
    private val confirmedAudio: (String) -> Boolean = { true },
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
        var lifecycle = false
        var playingMs = 0L
        var lastPositionMs: Long? = null
        var seekedToEnd = false
        var manual = false
        var lastCheckpointMs = 0L
    }

    private var listen: Listen? = null
    private var cadence: Job? = null
    private var transitioned = false
    private var transitionReason: Int? = null
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
        transitionReason = reason
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (oldPosition.mediaItem?.mediaId == listen?.mediaId) outgoingPosition = oldPosition
        if (reason == Player.DISCONTINUITY_REASON_SEEK) rebase(newPosition.positionMs)
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
            finish(reason = endReasonForTransition(previous))
            startCurrent(manual = manualTransition(transitionReason))
        } else {
            if (outgoingPosition != null) emit(progress = true)
            refreshCurrent()
            if (player.playbackState == Player.STATE_ENDED || player.playbackState == Player.STATE_IDLE) {
                finish(reason = "natural")
            } else {
                // Seek and pause boundaries are event-driven. Seek time never increases playedMs.
                if (outgoingPosition != null || !player.isPlaying) emit(progress = true)
                if (player.isPlaying) listen?.playingSinceMs = nowMs()
                updateCadence()
            }
        }
        transitioned = false
        transitionReason = null
        outgoingPosition = null
    }

    fun close() {
        if (closed) return
        closed = true
        closeSegment()
        outgoingPosition?.let { listen?.positionMs = it.positionMs.coerceAtLeast(0) }
            ?: refreshCurrent()
        finish(reason = "interrupted")
        player.removeListener(this)
    }

    private fun manualTransition(reason: Int?): Boolean = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK

    private fun endReasonForTransition(previous: Listen?): String {
        val endedNaturally = player.playbackState == Player.STATE_ENDED
        return when {
            endedNaturally -> if (previous?.seekedToEnd == true) "seek_out" else "natural"
            else -> "replaced"
        }
    }

    private fun startCurrent(manual: Boolean = false) {
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        listen =
            Listen(mediaId, resolveTrack(mediaId)).also {
                it.manual = manual
                it.lifecycle = lifecycleReporter(mediaId)
            }
        refreshCurrent()
        if (player.isPlaying) listen?.playingSinceMs = nowMs()
        updateCadence()
    }

    private fun refreshCurrent() {
        val current = listen ?: return
        if (current.mediaId != player.currentMediaItem?.mediaId) return
        current.positionMs = player.currentPosition.coerceAtLeast(0)
        if (current.track == null) current.track = resolveTrack(current.mediaId)
        // The accepted provider is unknown until the uncached stream resolves, so decide again on
        // every refresh instead of freezing a pre-resolution answer.
        if (!current.lifecycle && lifecycleReporter(current.mediaId)) {
            // The accepted provider just became known: restart the cadence on the lifecycle path so
            // early checkpoints are not lost to the generic thirty-second interval.
            current.lifecycle = true
            cadence?.cancel()
            cadence = null
            updateCadence()
        }
        current.durationMs = player.duration.takeIf { it > 0 }
            ?: current.track
                ?.duration
                ?.takeIf { it > 0 }
                ?.toLong()
                ?.times(1_000)
            ?: 0L
        countPositionDelta(current)
    }

    /**
     * Real listening time: forward deltas up to two seconds count only while audio is actually
     * playing and confirmed. A jump is a seek, never time, and a paused forward seek cannot add any.
     */
    private fun countPositionDelta(current: Listen) {
        val last = current.lastPositionMs
        current.lastPositionMs = current.positionMs
        if (last == null) return
        val delta = current.positionMs - last
        val audible = player.isPlaying && confirmedAudio(current.mediaId)
        when {
            delta in 1..MAX_PROGRESS_DELTA_MS && audible -> current.playingMs += delta
            delta != 0L -> current.seekedToEnd = nearEnd(current)
        }
    }

    /** An explicit seek rebases the sample baseline, so the jump is never credited as listening. */
    private fun rebase(positionMs: Long) {
        val current = listen ?: return
        if (current.mediaId != player.currentMediaItem?.mediaId) return
        current.positionMs = positionMs.coerceAtLeast(0)
        current.lastPositionMs = current.positionMs
        current.seekedToEnd = nearEnd(current)
    }

    private fun nearEnd(current: Listen): Boolean =
        current.durationMs > 0 && current.positionMs >= (current.durationMs - END_TOLERANCE_MS).coerceAtLeast(0)

    private fun closeSegment() {
        val current = listen ?: return
        current.playingSinceMs?.let { current.playedMs += (nowMs() - it).coerceAtLeast(0) }
        current.playingSinceMs = null
    }

    private fun playedMs(current: Listen): Long = current.playedMs + (current.playingSinceMs?.let { (nowMs() - it).coerceAtLeast(0) } ?: 0)

    private fun emit(progress: Boolean) {
        val current = listen ?: return
        val track = current.track ?: return
        refreshCurrent()
        val sample =
            AccountListenProgress(
                playedMs(current),
                current.durationMs,
                current.positionMs,
                progress,
                current.playbackSessionId,
                endReason = if (progress) null else "replaced",
                playingMs = current.playingMs,
                manual = current.manual,
            )
        if (progress && !current.lifecycle && !countsAsPlay(sample.playedMs, sample.durationMs)) return
        if (progress && sample == current.lastProgress) return
        if (progress) current.lastProgress = sample
        report(track, sample)
    }

    private fun finish(reason: String) {
        cadence?.cancel()
        cadence = null
        val current = listen ?: return
        // Credit the audible time since the last sample before closing the listen.
        val wasPlaying = current.lifecycle && current.playedMs > 0
        closeSegment()
        refreshCurrent()
        if (wasPlaying) {
            val delta = current.positionMs - (current.lastPositionMs ?: current.positionMs)
            if (delta in 1..MAX_PROGRESS_DELTA_MS) current.playingMs += delta
        }
        val finalReason = if (reason == "natural" && current.seekedToEnd) "seek_out" else reason
        val track = current.track
        val sample =
            AccountListenProgress(
                playedMs(current),
                current.durationMs,
                current.positionMs,
                progress = false,
                playbackSessionId = current.playbackSessionId,
                endReason = finalReason,
                playingMs = current.playingMs,
                manual = current.manual,
            )
        listen = null
        if (track == null) return
        if (!current.lifecycle && !countsAsPlay(sample.playedMs, sample.durationMs)) return
        report(track, sample)
    }

    private fun updateCadence() {
        if (listen == null || !player.isPlaying) {
            cadence?.cancel()
            cadence = null
            return
        }
        if (cadence?.isActive == true) return
        cadence =
            scope.launch {
                // The event callback re-arms the playing segment, so the loop is bounded by the
                // listen and playback, not by that transient marker.
                while (listen != null && player.isPlaying) {
                    val current = listen ?: break
                    // The branch is chosen per iteration, so a listen that turns out to be a
                    // lifecycle provider switches cadence without waiting for the next track.
                    if (current.lifecycle) {
                        delay(LIFECYCLE_SAMPLE_MS)
                        if (!player.isPlaying || listen !== current) break
                        refreshCurrent()
                        if (current.playingMs - current.lastCheckpointMs >= LIFECYCLE_CHECKPOINT_MS) {
                            current.lastCheckpointMs = current.playingMs
                            emit(progress = true)
                        }
                    } else {
                        val untilQualified = playThresholdMs(current.durationMs) - playedMs(current)
                        val wait = if (untilQualified > 0) minOf(REPORT_INTERVAL_MS, untilQualified) else REPORT_INTERVAL_MS
                        // Wait in one-second steps so a provider that only becomes known after the
                        // uncached stream resolves still moves onto the lifecycle path promptly.
                        var waited = 0L
                        while (waited < wait) {
                            delay(LIFECYCLE_SAMPLE_MS)
                            waited += LIFECYCLE_SAMPLE_MS
                            if (!player.isPlaying || listen !== current) break
                            if (lifecycleReporter(current.mediaId)) {
                                current.lifecycle = true
                                break
                            }
                        }
                        if (!player.isPlaying || listen !== current) break
                        if (current.lifecycle) continue
                        refreshCurrent()
                        emit(progress = true)
                    }
                }
            }
    }

    private companion object {
        const val REPORT_INTERVAL_MS = 30_000L
        const val LIFECYCLE_SAMPLE_MS = 1_000L
        const val LIFECYCLE_CHECKPOINT_MS = 5_000L
        const val MAX_PROGRESS_DELTA_MS = 2_000L
        const val END_TOLERANCE_MS = 3_000L
    }
}

internal fun AccountPlayHistory.reportProgress(
    track: MusicTrack,
    progress: AccountListenProgress,
) = onListened(
    track,
    progress.playedMs,
    progress.durationMs,
    progress.positionMs,
    progress.progress,
    progress.playbackSessionId,
    progress.endReason,
    progress.playingMs,
    progress.manual,
)
