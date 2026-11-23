package io.github.aedev.flow.player.audio

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.analytics.AnalyticsListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Confirms that a media open actually produced audio before playback is reported as Playing. A
 * resolved stream can be Ready with its timeline advancing and still have no selected audio track,
 * which looks like a playing song while nothing sounds. Confirmation is Media3's audio-position
 * advancement, bound to the open attempt that produced it: an open, retry, queue change or source
 * replacement discards stale callbacks. A Ready audio-only attempt with no selected audio track,
 * an attempt that ends before audio starts, or one whose selected track never produces audio
 * within the bound, is rejected so the service can recover at the same position.
 */
internal class MusicAudioStartGate(
    private val player: Player,
    private val scope: CoroutineScope,
    private val onSilentStart: (mediaId: String, positionMs: Long) -> Unit,
    private val startTimeoutMs: Long = START_TIMEOUT_MS,
) : AnalyticsListener,
    Player.Listener {
    private var confirmedMediaId: String? = null
    private var pendingMediaId: String? = null
    private var pendingPositionMs: Long? = null
    private var startupCheck: Job? = null

    /** Whether [mediaId]'s current open has confirmed audio output. */
    fun confirmed(mediaId: String): Boolean = confirmedMediaId == mediaId

    /**
     * The frozen position of [mediaId]'s unconfirmed open, for the exposed player to hold while
     * the listener has requested audio and the output is still pending; null when confirmed.
     */
    fun pendingHold(mediaId: String): Long? = pendingPositionMs?.takeIf { pendingMediaId == mediaId }

    /** The attempt changed: a later advancement or rejection cannot be attributed to the old one. */
    fun attemptChanged() {
        startupCheck?.cancel()
        startupCheck = null
        pendingMediaId = null
        pendingPositionMs = null
    }

    /** The same item's source was replaced or re-prepared: its audio start must be re-confirmed. */
    fun sourceReplaced(mediaId: String) {
        if (confirmedMediaId == mediaId) confirmedMediaId = null
        if (pendingMediaId == mediaId) attemptChanged()
    }

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long,
    ) {
        confirmCurrent()
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        if (confirmedMediaId == mediaId || pendingMediaId != mediaId) return
        confirmedMediaId = mediaId
        pendingMediaId = null
        pendingPositionMs = null
        startupCheck?.cancel()
        startupCheck = null
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
            )
        ) {
            return
        }
        monitor(player)
    }

    private fun monitor(player: Player) {
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        if (confirmed(mediaId)) return
        val wantsAudio =
            player.playWhenReady &&
                player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        val rejected =
            player.playbackState == Player.STATE_ENDED ||
                (player.playbackState == Player.STATE_READY && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
        pendingMediaId = mediaId
        // The exposed position stays at the attempt's base while a silent clock might advance.
        if (pendingPositionMs == null) pendingPositionMs = player.currentPosition.coerceAtLeast(0L)
        if (!wantsAudio && !rejected) {
            // Nothing the listener asked for yet: stay pending without a deadline, paused mid-open.
            startupCheck?.cancel()
            startupCheck = null
            return
        }
        if (!rejected && startupCheck?.isActive == true) return
        startupCheck?.cancel()
        val positionAtSchedule = pendingPositionMs ?: player.currentPosition.coerceAtLeast(0L)
        startupCheck =
            scope.launch {
                if (rejected) yield() else delay(startTimeoutMs)
                if (player.currentMediaItem?.mediaId != mediaId || confirmed(mediaId) || pendingMediaId != mediaId) return@launch
                // The immediate rejection needs the no-audio-track state to hold; the bound exists
                // for a selected track that still never produced audio output.
                if (rejected && player.playbackState != Player.STATE_ENDED &&
                    !(player.playbackState == Player.STATE_READY && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO))
                ) {
                    return@launch
                }
                attemptChanged()
                onSilentStart(mediaId, positionAtSchedule)
            }
    }

    private fun confirmCurrent() {
        val mediaId = player.currentMediaItem?.mediaId?.takeIf { it.isNotBlank() } ?: return
        if (confirmedMediaId == mediaId || pendingMediaId != mediaId) return
        confirmedMediaId = mediaId
        pendingMediaId = null
        pendingPositionMs = null
        startupCheck?.cancel()
        startupCheck = null
    }

    private companion object {
        const val START_TIMEOUT_MS = 20_000L
    }
}
