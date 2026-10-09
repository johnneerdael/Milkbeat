package io.github.aedev.flow.player.diagnostics

import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong

/** Only fixed event/category names and numeric fields cross this release-compatible log boundary. */
object PlaybackTrace {
    const val TAG = "MilkbeatTrace"
    internal val logger = PlaybackTraceLogger(SystemClock::elapsedRealtime) { Log.i(TAG, it) }

    val enabled: Boolean get() = logger.enabled

    fun setEnabled(enabled: Boolean) = logger.setEnabled(enabled)

    fun event(
        event: TraceEvent,
        vararg fields: Pair<TraceField, Long>,
        category: TraceCategory = TraceCategory.NONE,
    ) = logger.event(event, *fields, category = category)

    fun start(
        event: TraceEvent,
        category: TraceCategory = TraceCategory.NONE,
    ): TraceSpan = logger.start(event, category)
}

/** Injectable clock and sink allow verification without Android logging or playback side effects. */
internal class PlaybackTraceLogger(
    private val clock: () -> Long,
    private val sink: (String) -> Unit,
) {
    @Volatile
    var enabled = false
        private set
    private val nextId = AtomicLong()

    fun setEnabled(value: Boolean) {
        enabled = value
        if (value) event(TraceEvent.LOGGING_ENABLED)
    }

    fun event(
        event: TraceEvent,
        vararg fields: Pair<TraceField, Long>,
        category: TraceCategory = TraceCategory.NONE,
    ) {
        if (!enabled) return
        sink(
            buildString {
                append("t_ms=${clock()} event=${event.name.lowercase()}")
                if (category != TraceCategory.NONE) append(" category=${category.name.lowercase()}")
                fields.forEach { (field, value) -> append(" ${field.name.lowercase()}=$value") }
            },
        )
    }

    fun start(
        event: TraceEvent,
        category: TraceCategory,
    ): TraceSpan {
        if (!enabled) return TraceSpan(null, 0L, 0L, category, clock)
        val span = TraceSpan(this, nextId.incrementAndGet(), clock(), category, clock)
        span.event(event)
        return span
    }
}

class TraceSpan internal constructor(
    private val logger: PlaybackTraceLogger?,
    private val id: Long,
    private val startedMs: Long,
    private val category: TraceCategory,
    private val clock: () -> Long,
) {
    fun event(
        event: TraceEvent,
        vararg fields: Pair<TraceField, Long>,
    ) {
        val active = logger ?: return
        if (!active.enabled) return
        active.event(event, TraceField.TRACE_ID to id, TraceField.ELAPSED_MS to (clock() - startedMs), *fields, category = category)
    }
}

enum class TraceEvent {
    LOGGING_ENABLED,
    PLAY,
    PAUSE,
    TOGGLE_PLAY_PAUSE,
    STOP,
    SEEK,
    PLAY_TRACK_REQUESTED,
    PLAY_TRACK,
    PLAY_NEXT,
    PLAY_PREVIOUS,
    PLAY_FROM_QUEUE,
    VIEW_SWITCH,
    VIDEO_SELECTION,
    VIDEO_SURFACE_ACQUIRED,
    VIDEO_SURFACE_RELEASED,
    KNOWN_ID_BYPASS,
    CROSS_PROVIDER_SEARCH,
    RESOLUTION_STARTED,
    RESOLUTION_FINISHED,
    RESOLUTION_FAILED,
    PROVIDER_ATTEMPT,
    PROVIDER_RESULT,
    SOURCE_PREPARED,
    HISTORY_QUEUED,
    HISTORY_DISABLED,
    HISTORY_REPORT_STARTED,
    HISTORY_REPORT_FINISHED,
    RUNTIME_QUEUED,
    RUNTIME_PERMIT_ACQUIRED,
    RUNTIME_CONTEXT_ACQUIRED,
    RUNTIME_EXECUTION_STARTED,
    RUNTIME_EXECUTION_FINISHED,
    RUNTIME_FINISHED,
    HTTP_STARTED,
    HTTP_FINISHED,
    PLAYBACK_SNAPSHOT,
    PLAYBACK_STATE,
    PLAY_WHEN_READY,
    IS_PLAYING,
    LOADING,
    POSITION_DISCONTINUITY,
    TRACKS_CHANGED,
    AUDIO_DECODER_INITIALIZED,
    AUDIO_DECODER_RELEASED,
    AUDIO_ENABLED,
    AUDIO_DISABLED,
    AUDIO_TRACK_CREATED,
    AUDIO_TRACK_INITIALIZED,
    AUDIO_TRACK_RELEASED,
    AUDIO_SESSION_CHANGED,
    AUDIO_POSITION_ADVANCING,
    AUDIO_UNDERRUN,
    AUDIO_SINK_ERROR,
    AUDIO_CODEC_ERROR,
    AUDIO_FORMAT,
    VIDEO_DECODER_INITIALIZED,
    VIDEO_DECODER_RELEASED,
    VIDEO_ENABLED,
    VIDEO_DISABLED,
    VIDEO_FORMAT,
    FIRST_VIDEO_FRAME,
    DROPPED_VIDEO_FRAMES,
    VIDEO_SIZE,
    SURFACE_SIZE,
    PLAYER_ERROR,
}

enum class TraceCategory {
    NONE,
    AUDIO_RESOLVE,
    VIDEO_RESOLVE,
    SEARCH,
    WARM_UP,
    OTHER_OPERATION,
    HISTORY,
    YOUTUBE_PLAYER,
    YOUTUBE_SEARCH,
    YOUTUBE_BROWSE,
    YOUTUBE_NEXT,
    YOUTUBE_API_OTHER,
    YOUTUBE_PAGE,
    PLAYER_SCRIPT,
    MEDIA_DELIVERY,
    OTHER_HTTP,
    AUDIO_AAC,
    AUDIO_OPUS,
    AUDIO_FLAC,
    AUDIO_PCM,
    AUDIO_OTHER,
    VIDEO_VP9,
    VIDEO_AV1,
    VIDEO_H264,
    VIDEO_HEVC,
    VIDEO_OTHER,
}

enum class TraceField {
    TRACE_ID,
    ELAPSED_MS,
    QUEUE_WAIT_MS,
    EXECUTION_MS,
    DURATION_MS,
    SUCCESS,
    STATUS,
    BYTES,
    POSITION_MS,
    BUFFERED_MS,
    STATE,
    REASON,
    PLAY_WHEN_READY,
    IS_PLAYING,
    LOADING,
    SHOW_VIDEO,
    VIDEO_AVAILABLE,
    VIDEO_ENABLED,
    SURFACES,
    GENERATION,
    HEAD_FRAMES,
    SESSION_ID,
    SAMPLE_RATE,
    CHANNELS,
    ENCODING,
    BUFFER_BYTES,
    BUFFER_MS,
    SINCE_LAST_FEED_MS,
    INIT_MS,
    INIT_AT_MS,
    WINDOW_INDEX,
    CURRENT_WINDOW_INDEX,
    EVENT_REALTIME_MS,
    WIDTH,
    HEIGHT,
    COUNT,
    FRAME_AT_MS,
    PLAYOUT_START_SYSTEM_MS,
    ERROR_CODE,
    OFFLOAD,
    TUNNELING,
    REUSE_RESULT,
    VIEW_MODE,
    OUTPUT_MONITORABLE,
    SOFTWARE_DECODER,
    PROVIDER_INDEX,
    MATCH_FOUND,
    PLAYED_MS,
    PROGRESS,
}
