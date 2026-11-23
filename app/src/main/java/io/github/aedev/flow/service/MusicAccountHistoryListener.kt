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

    private data class Discontinuity(
        val outgoing: Player.PositionInfo,
        val incoming: Player.PositionInfo,
        val isSeek: Boolean,
    )

    private var transitionReason: Int? = null
    private var discontinuity: Discontinuity? = null
    private var reopeningMediaId: String? = null
    private var closed = false

    init {
        player.addListener(this)
        startCurrent()
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        transitionReason = reason
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (oldPosition.mediaItem?.mediaId != listen?.mediaId) return
        val previous = discontinuity
        val isSeek = reason == Player.DISCONTINUITY_REASON_SEEK || reason == Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT
        discontinuity = Discontinuity(previous?.outgoing ?: oldPosition, newPosition, isSeek || previous?.isSeek == true)
    }

    /** Keep one listening occurrence when the service replaces its MediaSource at the same position. */
    fun sourceWillReopen(mediaId: String) {
        if (listen?.mediaId == mediaId) reopeningMediaId = mediaId
    }

    fun sourceRecoveryExhausted(mediaId: String) {
        if (listen?.mediaId != mediaId) return
        reopeningMediaId = null
        refreshCurrent(audible = listen?.playingSinceMs != null)
        closeSegment()
        finish(reason = "interrupted")
    }

    // Transition callbacks expose the incoming position. The discontinuity callback owns the
    // outgoing position; wait for the whole event batch so callback order cannot erase it.
    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (closed) return
        val previous = listen
        val mediaId = player.currentMediaItem?.mediaId
        val reason = transitionReason
        val change = discontinuity
        transitionReason = null
        discontinuity = null

        val reopening = previous != null && previous.mediaId == mediaId && mediaId == reopeningMediaId
        val replacement = reopening && reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED
        if ((reason != null && !replacement) || previous?.mediaId != mediaId) {
            change?.let { markOutgoing(it) }
            closeSegment()
            reopeningMediaId = null
            finish(
                reason =
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    ) {
                        "natural"
                    } else {
                        "replaced"
                    },
            )
            startCurrent(manual = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
            return
        }

        val wasAudible = previous?.playingSinceMs != null
        if (change != null) {
            markOutgoing(change)
            if (change.isSeek && !reopening) {
                previous?.let {
                    samplePosition(it, change.incoming.positionMs, audible = false)
                    it.seekedToEnd = nearEnd(it)
                }
            }
        }
        refreshCurrent(audible = wasAudible && change == null)
        closeSegment()
        when {
            player.playbackState == Player.STATE_ENDED -> {
                finish(reason = "natural")
            }

            player.playbackState == Player.STATE_IDLE && !reopening && player.playerError == null -> {
                finish(reason = "interrupted")
            }

            else -> {
                if (change != null || !isAudible()) emitProgress()
                if (isAudible()) {
                    reopeningMediaId = null
                    listen?.playingSinceMs = nowMs()
                }
                updateCadence()
            }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        val change = discontinuity
        if (change != null) markOutgoing(change) else refreshCurrent(audible = listen?.playingSinceMs != null)
        closeSegment()
        finish(reason = "interrupted")
        player.removeListener(this)
    }

    private fun markOutgoing(change: Discontinuity) {
        val current = listen ?: return
        samplePosition(current, change.outgoing.positionMs, audible = current.playingSinceMs != null)
    }

    private fun startCurrent(manual: Boolean = false) {
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        listen =
            Listen(mediaId, resolveTrack(mediaId)).also {
                it.manual = manual
                it.lifecycle = lifecycleReporter(mediaId)
            }
        refreshCurrent()
        if (isAudible()) listen?.playingSinceMs = nowMs()
        updateCadence()
    }

    private fun isAudible(): Boolean = player.isPlaying && listen?.mediaId?.let(confirmedAudio) == true

    private fun refreshCurrent(audible: Boolean = isAudible()) {
        val current = listen ?: return
        if (current.mediaId != player.currentMediaItem?.mediaId) return
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
        samplePosition(current, player.currentPosition, audible)
    }

    /** A bounded position delta counts only inside an audible segment. */
    private fun samplePosition(
        current: Listen,
        positionMs: Long,
        audible: Boolean,
    ) {
        val last = current.lastPositionMs
        current.positionMs = positionMs.coerceAtLeast(0)
        current.lastPositionMs = current.positionMs
        if (last == null || !audible) return
        val delta = current.positionMs - last
        if (delta in 1..MAX_PROGRESS_DELTA_MS) current.playingMs += delta
    }

    private fun nearEnd(current: Listen): Boolean =
        current.durationMs > 0 && current.positionMs >= (current.durationMs - END_TOLERANCE_MS).coerceAtLeast(0)

    private fun closeSegment() {
        val current = listen ?: return
        current.playingSinceMs?.let { current.playedMs += (nowMs() - it).coerceAtLeast(0) }
        current.playingSinceMs = null
    }

    private fun playedMs(current: Listen): Long = current.playedMs + (current.playingSinceMs?.let { (nowMs() - it).coerceAtLeast(0) } ?: 0)

    private fun emitProgress() {
        val current = listen ?: return
        val track = current.track ?: return
        val sample =
            AccountListenProgress(
                playedMs(current),
                current.durationMs,
                current.positionMs,
                progress = true,
                current.playbackSessionId,
                playingMs = current.playingMs,
                manual = current.manual,
            )
        if (!current.lifecycle && !countsAsPlay(sample.playedMs, sample.durationMs)) return
        if (sample == current.lastProgress) return
        current.lastProgress = sample
        report(track, sample)
    }

    private fun finish(reason: String) {
        cadence?.cancel()
        cadence = null
        val current = listen ?: return
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
        if (!isAudible()) {
            cadence?.cancel()
            cadence = null
            return
        }
        if (cadence?.isActive == true) return
        cadence =
            scope.launch {
                // The event callback re-arms the playing segment, so the loop is bounded by the
                // listen and playback, not by that transient marker.
                while (isAudible()) {
                    val current = listen ?: break
                    // The branch is chosen per iteration, so a listen that turns out to be a
                    // lifecycle provider switches cadence without waiting for the next track.
                    if (current.lifecycle) {
                        delay(LIFECYCLE_SAMPLE_MS)
                        if (!isAudible() || listen !== current) break
                        refreshCurrent()
                        if (current.playingMs - current.lastCheckpointMs >= LIFECYCLE_CHECKPOINT_MS) {
                            current.lastCheckpointMs = current.playingMs
                            emitProgress()
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
                            if (!isAudible() || listen !== current) break
                            if (lifecycleReporter(current.mediaId)) {
                                current.lifecycle = true
                                break
                            }
                        }
                        if (!isAudible() || listen !== current) break
                        if (current.lifecycle) continue
                        refreshCurrent()
                        emitProgress()
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
