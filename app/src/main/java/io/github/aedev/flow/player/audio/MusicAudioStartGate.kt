package io.github.aedev.flow.player.audio

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Confirms that a media open actually produced audio before playback is reported as Playing. A
 * resolved stream can be Ready with its timeline advancing and still have no selected audio track,
 * which looks like a playing song while nothing sounds.
 *
 * Confirmation is Media3's audio-position advancement for *this* open: the pending hold records the
 * attempt's window uid, and audio advancing for any other window (an earlier source, a preloaded
 * neighbour) cannot confirm it. An open, retry, queue change or source replacement discards the
 * pending attempt. A Ready audio-only attempt with no selected audio track, an attempt that ends
 * before audio starts, or one whose selected track never produces audio within the bound, is
 * rejected so the service can recover at the same position.
 *
 * Gapless queue changes are the exception to "advancement per open": Media3 keeps the same audio
 * output running into the next window and its position tracker reports advancement only once, after
 * output starts. A window change that arrives while that output is still playing is therefore
 * already sounding and is confirmed by the continuous output itself.
 */
internal class MusicAudioStartGate(
    private val player: Player,
    private val scope: CoroutineScope,
    private val onSilentStart: (mediaId: String, positionMs: Long) -> Unit,
    /** The exposed player refreshes whenever a hold starts, ends or is replaced. */
    private val onHoldChanged: () -> Unit = {},
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
) : AnalyticsListener,
    Player.Listener {
    private data class Occurrence(
        val mediaId: String,
        val windowUid: Any?,
    )

    private class Pending(
        val occurrence: Occurrence,
        var positionMs: Long,
    )

    private var confirmed: Occurrence? = null
    private var pending: Pending? = null
    private var startupCheck: Job? = null

    /**
     * Whether the audio output has kept playing since [confirmed] was established. Pausing,
     * seeking, disabling the renderer or rebuilding the track all end it; a gapless queue change
     * does not, because it reuses the running output.
     */
    private var outputContinuous = false

    /** Whether [mediaId]'s current open has confirmed audio output. */
    fun confirmed(mediaId: String): Boolean = confirmed?.mediaId == mediaId && confirmed == currentOccurrence()

    /** The frozen position of the unconfirmed occurrence, while the decoder opens. */
    fun pendingHold(mediaId: String): Long? =
        pending?.takeIf { it.occurrence.mediaId == mediaId && it.occurrence == currentOccurrence() }?.positionMs

    /** The attempt changed: a later advancement or rejection cannot be attributed to the old one. */
    fun attemptChanged() {
        val changed = pending != null || confirmed != null
        cancelWatchdog()
        pending = null
        confirmed = null
        outputContinuous = false
        if (changed) onHoldChanged()
    }

    /** The same item's source was replaced or re-prepared: its audio start must be re-confirmed. */
    fun sourceReplaced(mediaId: String) {
        if (confirmed?.mediaId == mediaId || pending?.occurrence?.mediaId == mediaId) attemptChanged()
    }

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long,
    ) {
        outputContinuous = true
        val opening = pending ?: return
        val occurrence = opening.occurrence
        if (occurrence != currentOccurrence() || eventTimeWindowUid(eventTime) != occurrence.windowUid) return
        // A re-prepare can reuse its playlist window but has a new media-period sequence number.
        val period = eventTime.mediaPeriodId
        if (period != null && eventTime.currentMediaPeriodId != null && period != eventTime.currentMediaPeriodId) return
        confirmed = occurrence
        pending = null
        cancelWatchdog()
        onHoldChanged()
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        // A rebuilt output restarts playout, so the next advancement must confirm the current open.
        outputContinuous = false
    }

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        outputContinuous = false
    }

    override fun onAudioDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        outputContinuous = false
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        // Only a seamless automatic transition keeps the output playing through a window change.
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) outputContinuous = false
        if (reason != Player.DISCONTINUITY_REASON_SEEK) return
        val opening = pending ?: return
        if (opening.occurrence != currentOccurrence()) return
        opening.positionMs = newPosition.positionMs.coerceAtLeast(0L)
        onHoldChanged()
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        if (!events.containsAny(
                Player.EVENT_PLAYBACK_STATE_CHANGED,
                Player.EVENT_IS_PLAYING_CHANGED,
                Player.EVENT_TRACKS_CHANGED,
                Player.EVENT_POSITION_DISCONTINUITY,
                Player.EVENT_TIMELINE_CHANGED,
                Player.EVENT_PLAY_WHEN_READY_CHANGED,
                Player.EVENT_PLAYBACK_SUPPRESSION_REASON_CHANGED,
            )
        ) {
            return
        }
        monitor()
    }

    private fun monitor() {
        val occurrence = currentOccurrence()
        if (occurrence == null) {
            attemptChanged()
            return
        }
        if (confirmed == occurrence) return
        if (confirmed != null && outputContinuous && wantsAudio() && !hasNoAudio()) {
            // Gapless playback kept the output running into this window; there is no second
            // advancement to wait for, so the continuous output itself confirms the new open.
            confirmed = occurrence
            pending = null
            cancelWatchdog()
            onHoldChanged()
            return
        }
        confirmed = null
        val opening =
            pending?.takeIf { it.occurrence == occurrence } ?: Pending(occurrence, player.currentPosition.coerceAtLeast(0L)).also {
                cancelWatchdog()
                pending = it
                onHoldChanged()
            }
        if (!wantsAudio()) {
            cancelWatchdog()
            return
        }
        val rejected = hasNoAudio()
        if (!rejected && startupCheck?.isActive == true) return
        cancelWatchdog()
        startupCheck =
            scope.launch {
                if (rejected) yield() else delay(startTimeoutMs)
                if (pending !== opening || currentOccurrence() != occurrence || !wantsAudio()) return@launch
                if (rejected && !hasNoAudio()) return@launch
                attemptChanged()
                onSilentStart(occurrence.mediaId, opening.positionMs)
            }
    }

    private fun wantsAudio(): Boolean =
        player.playWhenReady &&
            player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            player.playbackState != Player.STATE_IDLE

    private fun hasNoAudio(): Boolean =
        player.playbackState == Player.STATE_ENDED ||
            (player.playbackState == Player.STATE_READY && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))

    private fun cancelWatchdog() {
        startupCheck?.cancel()
        startupCheck = null
    }

    private fun currentOccurrence(): Occurrence? =
        player.currentMediaItem
            ?.mediaId
            ?.takeIf { it.isNotBlank() }
            ?.let { Occurrence(it, currentWindowUid()) }

    private fun eventTimeWindowUid(eventTime: AnalyticsListener.EventTime): Any? {
        val windowIndex = eventTime.windowIndex
        if (windowIndex !in 0 until eventTime.timeline.windowCount) return null
        val window = Timeline.Window()
        eventTime.timeline.getWindow(windowIndex, window)
        return window.uid
    }

    private fun currentWindowUid(): Any? {
        val timeline = player.currentTimeline
        val index = player.currentMediaItemIndex
        if (index !in 0 until timeline.windowCount) return null
        val window = Timeline.Window()
        timeline.getWindow(index, window)
        return window.uid
    }

    private companion object {
        const val START_TIMEOUT_MS = 20_000L
    }
}
