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
import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.player.diagnostics.TraceCategory
import io.github.aedev.flow.player.diagnostics.TraceEvent
import io.github.aedev.flow.player.diagnostics.TraceField
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import nl.neerdael.milkbeat.sabr.manifest.SabrManifest
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
    private var videoClockOffsetMs = C.TIME_UNSET
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
        if (source == nextSource) return
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
        if (waiting) {
            PlaybackTrace.event(
                TraceEvent.VIDEO_PREBUFFER_STARTED,
                TraceField.POSITION_MS to current.currentPosition,
                TraceField.DURATION_MS to INITIAL_VIDEO_BUFFER_MS,
                category = TraceCategory.VIDEO_RESOLVE,
            )
        }
    }

    fun reset() {
        if (waiting) PlaybackTrace.event(TraceEvent.VIDEO_PREBUFFER_ABORTED, category = TraceCategory.VIDEO_RESOLVE)
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
        if (!requested || prepared.value || !isCurrentVideo(eventTime, mediaLoadData)) return
        val mediaStart = mediaLoadData.mediaStartTimeMs
        val mediaEnd = mediaLoadData.mediaEndTimeMs
        if (mediaStart == C.TIME_UNSET || mediaEnd == C.TIME_UNSET || mediaEnd <= mediaStart) return
        val periodUid = eventTime.mediaPeriodId?.periodUid ?: return
        val offset = mediaTimeOffsetMs(eventTime.timeline, periodUid) ?: return
        rebaseCoverage(offset)
        val start = mediaStart + offset
        val end = mediaEnd + offset
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
        if (requested && !prepared.value && matchesCurrentSource(eventTime)) {
            waiting = false
            videoStartMs = C.TIME_UNSET
            videoEndMs = C.TIME_UNSET
        }
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
        if (!requested || prepared.value || source != currentSource() || videoEndMs == C.TIME_UNSET ||
            !current.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)
        ) {
            return
        }
        val periodUid = source?.periodUid ?: return
        val offset = mediaTimeOffsetMs(current.currentTimeline, periodUid) ?: return
        rebaseCoverage(offset)
        val position = current.currentPosition
        val remaining = (current.duration - position).takeIf { current.duration > 0 && it > 0 }
        val required = minOf(INITIAL_VIDEO_BUFFER_MS, remaining ?: INITIAL_VIDEO_BUFFER_MS)
        if (videoStartMs <= position && videoEndMs - position >= required && current.totalBufferedDuration >= required) {
            PlaybackTrace.event(
                TraceEvent.VIDEO_PREBUFFER_READY,
                TraceField.POSITION_MS to position,
                TraceField.BUFFERED_MS to current.totalBufferedDuration,
                TraceField.MEDIA_START_MS to videoStartMs,
                TraceField.MEDIA_END_MS to videoEndMs,
                category = TraceCategory.VIDEO_RESOLVE,
            )
            waiting = false
            prepared.value = true
        }
    }

    private fun mediaTimeOffsetMs(
        timeline: Timeline,
        periodUid: Any,
    ): Long? {
        val periodIndex = timeline.getIndexOfPeriod(periodUid)
        if (periodIndex == C.INDEX_UNSET) return null
        val period = timeline.getPeriodByUid(periodUid, Timeline.Period())
        val window = timeline.getWindow(period.windowIndex, Timeline.Window())
        val manifest = window.manifest as? SabrManifest
        val dispatcherOffset =
            if (manifest == null) {
                0L
            } else {
                val index = periodIndex - window.firstPeriodIndex
                if (index !in 0 until manifest.periodCount) return null
                manifest.getPeriod(index).startMs
            }
        // SABR's dispatcher adds period.startMs; DASH/HLS media events stay period-relative.
        return period.positionInWindowMs - dispatcherOffset
    }

    private fun rebaseCoverage(offsetMs: Long) {
        if (videoEndMs != C.TIME_UNSET && videoClockOffsetMs != C.TIME_UNSET) {
            val delta = offsetMs - videoClockOffsetMs
            videoStartMs += delta
            videoEndMs += delta
        }
        videoClockOffsetMs = offsetMs
    }

    private companion object {
        const val INITIAL_VIDEO_BUFFER_MS = 2_000L
    }
}

internal val musicVideoJoinGate = MusicVideoJoinGate()
