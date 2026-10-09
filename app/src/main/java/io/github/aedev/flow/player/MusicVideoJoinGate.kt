package io.github.aedev.flow.player

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException

/** The first visible picture joins only after current-source video media covers the audio clock. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicVideoJoinGate :
    Player.Listener,
    AnalyticsListener {
    private data class Source(
        val windowUid: Any,
        val periodUid: Any,
    )

    private val prepared = MutableStateFlow(false)
    val ready = prepared.asStateFlow()

    @Volatile
    var waiting = false
        private set

    private var player: ExoPlayer? = null
    private var source: Source? = null
    private var requested = false
    private var videoStartMs = C.TIME_UNSET
    private var videoEndMs = C.TIME_UNSET
    private var audioReadyWindowUid: Any? = null

    val awaitingAudio: Boolean
        get() {
            val current = player ?: return false
            if (current.currentMediaItem
                    ?.localConfiguration
                    ?.uri
                    ?.scheme != MusicVideoItems.SCHEME
            ) {
                return false
            }
            val windowUid = currentSource()?.windowUid ?: return true
            if (current.playbackState == Player.STATE_READY || current.isPlaying) audioReadyWindowUid = windowUid
            return audioReadyWindowUid != windowUid
        }

    fun bind(nextPlayer: ExoPlayer?) {
        if (player === nextPlayer) return
        player?.removeListener(this)
        player?.removeAnalyticsListener(this)
        reset()
        audioReadyWindowUid = null
        player = nextPlayer
        nextPlayer?.addListener(this)
        nextPlayer?.addAnalyticsListener(this)
    }

    /** Invoke before enabling VIDEO in the selector so its initial readiness cannot stall audio. */
    fun begin() {
        val current = player ?: return
        requested = true
        val nextSource = currentSource() ?: return
        if (awaitingAudio) return
        if (source == nextSource && (waiting || prepared.value)) return
        source = nextSource
        videoStartMs = C.TIME_UNSET
        videoEndMs = C.TIME_UNSET
        // Local muxed files retain their normal Media3 behavior. Prepared network pictures use the
        // adaptive music-video item, whose per-track media load events supply actual video coverage.
        waiting = current.currentMediaItem
            ?.localConfiguration
            ?.uri
            ?.scheme == MusicVideoItems.SCHEME
        prepared.value = !waiting
    }

    fun reset() {
        requested = false
        waiting = false
        prepared.value = false
        source = null
        videoStartMs = C.TIME_UNSET
        videoEndMs = C.TIME_UNSET
    }

    override fun onMediaItemTransition(
        mediaItem: MediaItem?,
        reason: Int,
    ) {
        audioReadyWindowUid = null
        if (!requested) return
        reset()
        begin()
    }

    override fun onEvents(
        player: Player,
        events: Player.Events,
    ) {
        val nextSource = currentSource()
        if (requested && source != nextSource) {
            if (prepared.value && nextSource != null && source?.windowUid == nextSource.windowUid) {
                // A later period in the same picture keeps ordinary Media3 buffering behavior.
                source = nextSource
                videoStartMs = C.TIME_UNSET
                videoEndMs = C.TIME_UNSET
            } else {
                reset()
                begin()
            }
        }
        completeIfBuffered()
    }

    override fun onPlayerError(error: PlaybackException) = reset()

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
    ) {
        if (!waiting || !isCurrentVideo(eventTime, mediaLoadData)) return
        val start = mediaLoadData.mediaStartTimeMs
        val end = mediaLoadData.mediaEndTimeMs
        if (start == C.TIME_UNSET || end == C.TIME_UNSET || end <= start) return
        if (videoEndMs == C.TIME_UNSET || start > videoEndMs || end < videoStartMs) {
            videoStartMs = start
            videoEndMs = end
        } else {
            videoStartMs = minOf(videoStartMs, start)
            videoEndMs = maxOf(videoEndMs, end)
        }
        completeIfBuffered()
    }

    // An artificial ready result skips ExoPlayer's not-ready stream-error check. Give that check
    // back to Media3 on a real video load failure; never suppress its retry/error policy.
    override fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: IOException,
        wasCanceled: Boolean,
    ) {
        if (waiting && matchesCurrentSource(eventTime)) reset()
    }

    private fun currentSource(): Source? {
        val current = player ?: return null
        val timeline = current.currentTimeline
        if (timeline.isEmpty || current.currentPeriodIndex !in 0 until timeline.periodCount) return null
        return Source(
            timeline.getWindow(current.currentMediaItemIndex, Timeline.Window()).uid,
            timeline.getUidOfPeriod(current.currentPeriodIndex),
        )
    }

    private fun matchesCurrentSource(event: AnalyticsListener.EventTime): Boolean {
        val current = player ?: return false
        val expected = source ?: return false
        val eventPeriod = event.mediaPeriodId
        if (expected != currentSource() ||
            (
                eventPeriod != null &&
                    (eventPeriod.periodUid != expected.periodUid || eventPeriod != event.currentMediaPeriodId)
            )
        ) {
            return false
        }
        if (event.timeline.isEmpty || event.windowIndex !in 0 until event.timeline.windowCount ||
            event.timeline.getWindow(event.windowIndex, Timeline.Window()).uid != expected.windowUid
        ) {
            return false
        }
        return true
    }

    private fun isCurrentVideo(
        event: AnalyticsListener.EventTime,
        load: MediaLoadData,
    ): Boolean {
        val current = player ?: return false
        if (!matchesCurrentSource(event) || event.mediaPeriodId == null ||
            !current.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        ) {
            return false
        }
        if (load.dataType != C.DATA_TYPE_MEDIA) return false
        if (load.trackType == C.TRACK_TYPE_VIDEO) return true
        val format = load.trackFormat ?: return false
        return load.trackType == C.TRACK_TYPE_DEFAULT &&
            (MimeTypes.isVideo(format.sampleMimeType) || MimeTypes.getVideoMediaMimeType(format.codecs) != null)
    }

    private fun completeIfBuffered() {
        val current = player ?: return
        if (!waiting || source != currentSource() || videoEndMs == C.TIME_UNSET ||
            !current.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        ) {
            return
        }
        val position = current.currentPosition
        val remaining = (current.duration - position).takeIf { current.duration > 0 && it > 0 }
        val required = minOf(INITIAL_VIDEO_BUFFER_MS, remaining ?: INITIAL_VIDEO_BUFFER_MS)
        if (videoStartMs <= position && videoEndMs - position >= required && current.totalBufferedDuration >= required) {
            waiting = false
            prepared.value = true
        }
    }

    private companion object {
        const val INITIAL_VIDEO_BUFFER_MS = 2_000L
    }
}

internal val musicVideoJoinGate = MusicVideoJoinGate()
