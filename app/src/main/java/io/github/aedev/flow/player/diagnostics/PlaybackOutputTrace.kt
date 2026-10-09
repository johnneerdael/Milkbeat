package io.github.aedev.flow.player.diagnostics

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import io.github.aedev.flow.player.audio.AudioOutputProbe

/** Event-driven output evidence; never polls, changes selection, or owns a player/AudioTrack. */
@OptIn(UnstableApi::class)
internal class PlaybackOutputTrace(
    private val outputProbe: AudioOutputProbe? = null,
    private val trace: PlaybackTraceLogger = PlaybackTrace.logger,
) : AnalyticsListener {
    private fun emit(
        event: TraceEvent,
        time: AnalyticsListener.EventTime,
        vararg fields: Pair<TraceField, Long>,
        category: TraceCategory = TraceCategory.NONE,
    ) {
        if (!trace.enabled) return
        val sample = outputProbe?.read()
        trace.event(
            event,
            TraceField.EVENT_REALTIME_MS to time.realtimeMs,
            TraceField.WINDOW_INDEX to time.windowIndex.toLong(),
            TraceField.CURRENT_WINDOW_INDEX to time.currentWindowIndex.toLong(),
            TraceField.POSITION_MS to time.currentPlaybackPositionMs,
            TraceField.BUFFERED_MS to time.totalBufferedDurationMs,
            TraceField.GENERATION to (sample?.generation ?: -1L),
            TraceField.HEAD_FRAMES to (sample?.headFrames ?: -1L),
            TraceField.OUTPUT_MONITORABLE to if (outputProbe?.monitorable == true) 1L else 0L,
            *fields,
            category = category,
        )
    }

    override fun onEvents(
        player: Player,
        events: AnalyticsListener.Events,
    ) {
        if (!trace.enabled ||
            !events.containsAny(
                AnalyticsListener.EVENT_PLAYBACK_STATE_CHANGED,
                AnalyticsListener.EVENT_IS_PLAYING_CHANGED,
                AnalyticsListener.EVENT_TRACK_SELECTION_PARAMETERS_CHANGED,
                AnalyticsListener.EVENT_POSITION_DISCONTINUITY,
            )
        ) {
            return
        }
        val sample = outputProbe?.read()
        trace.event(
            TraceEvent.PLAYBACK_SNAPSHOT,
            TraceField.STATE to player.playbackState.toLong(),
            TraceField.IS_PLAYING to if (player.isPlaying) 1L else 0L,
            TraceField.PLAY_WHEN_READY to if (player.playWhenReady) 1L else 0L,
            TraceField.POSITION_MS to player.currentPosition,
            TraceField.BUFFERED_MS to player.totalBufferedDuration,
            TraceField.GENERATION to (sample?.generation ?: -1L),
            TraceField.HEAD_FRAMES to (sample?.headFrames ?: -1L),
            TraceField.OUTPUT_MONITORABLE to if (outputProbe?.monitorable == true) 1L else 0L,
        )
    }

    override fun onPlaybackStateChanged(
        eventTime: AnalyticsListener.EventTime,
        state: Int,
    ) = emit(TraceEvent.PLAYBACK_STATE, eventTime, TraceField.STATE to state.toLong())

    override fun onPlayWhenReadyChanged(
        eventTime: AnalyticsListener.EventTime,
        playWhenReady: Boolean,
        reason: Int,
    ) = emit(
        TraceEvent.PLAY_WHEN_READY,
        eventTime,
        TraceField.PLAY_WHEN_READY to if (playWhenReady) 1L else 0L,
        TraceField.REASON to reason.toLong(),
    )

    override fun onIsPlayingChanged(
        eventTime: AnalyticsListener.EventTime,
        isPlaying: Boolean,
    ) = emit(TraceEvent.IS_PLAYING, eventTime, TraceField.IS_PLAYING to if (isPlaying) 1L else 0L)

    override fun onIsLoadingChanged(
        eventTime: AnalyticsListener.EventTime,
        isLoading: Boolean,
    ) = emit(TraceEvent.LOADING, eventTime, TraceField.LOADING to if (isLoading) 1L else 0L)

    override fun onPositionDiscontinuity(
        eventTime: AnalyticsListener.EventTime,
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) = emit(TraceEvent.POSITION_DISCONTINUITY, eventTime, TraceField.REASON to reason.toLong())

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks,
    ) = emit(
        TraceEvent.TRACKS_CHANGED,
        eventTime,
        TraceField.VIDEO_ENABLED to if (tracks.isTypeSelected(androidx.media3.common.C.TRACK_TYPE_VIDEO)) 1L else 0L,
    )

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) = decoder(TraceEvent.AUDIO_DECODER_INITIALIZED, eventTime, decoderName, initializedTimestampMs, initializationDurationMs)

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) = decoder(TraceEvent.VIDEO_DECODER_INITIALIZED, eventTime, decoderName, initializedTimestampMs, initializationDurationMs)

    private fun decoder(
        event: TraceEvent,
        time: AnalyticsListener.EventTime,
        name: String,
        initializedMs: Long,
        durationMs: Long,
    ) = emit(
        event,
        time,
        TraceField.INIT_AT_MS to initializedMs,
        TraceField.INIT_MS to durationMs,
        TraceField.SOFTWARE_DECODER to
            if (name.startsWith("OMX.google.") || name.startsWith("c2.android.") || name.startsWith("c2.google.")) 1L else -1L,
    )

    override fun onAudioDecoderReleased(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
    ) = emit(TraceEvent.AUDIO_DECODER_RELEASED, eventTime)

    override fun onVideoDecoderReleased(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
    ) = emit(TraceEvent.VIDEO_DECODER_RELEASED, eventTime)

    override fun onAudioEnabled(
        eventTime: AnalyticsListener.EventTime,
        counters: DecoderCounters,
    ) = emit(TraceEvent.AUDIO_ENABLED, eventTime)

    override fun onAudioDisabled(
        eventTime: AnalyticsListener.EventTime,
        counters: DecoderCounters,
    ) = emit(TraceEvent.AUDIO_DISABLED, eventTime)

    override fun onVideoEnabled(
        eventTime: AnalyticsListener.EventTime,
        counters: DecoderCounters,
    ) = emit(TraceEvent.VIDEO_ENABLED, eventTime)

    override fun onVideoDisabled(
        eventTime: AnalyticsListener.EventTime,
        counters: DecoderCounters,
    ) = emit(TraceEvent.VIDEO_DISABLED, eventTime)

    override fun onAudioSessionIdChanged(
        eventTime: AnalyticsListener.EventTime,
        audioSessionId: Int,
    ) = emit(TraceEvent.AUDIO_SESSION_CHANGED, eventTime, TraceField.SESSION_ID to audioSessionId.toLong())

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long,
    ) = emit(TraceEvent.AUDIO_POSITION_ADVANCING, eventTime, TraceField.PLAYOUT_START_SYSTEM_MS to playoutStartSystemTimeMs)

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) = emit(
        TraceEvent.AUDIO_UNDERRUN,
        eventTime,
        TraceField.BUFFER_BYTES to bufferSize.toLong(),
        TraceField.BUFFER_MS to bufferSizeMs,
        TraceField.SINCE_LAST_FEED_MS to elapsedSinceLastFeedMs,
    )

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) = audioTrack(TraceEvent.AUDIO_TRACK_INITIALIZED, eventTime, audioTrackConfig)

    override fun onAudioTrackReleased(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) = audioTrack(TraceEvent.AUDIO_TRACK_RELEASED, eventTime, audioTrackConfig)

    private fun audioTrack(
        event: TraceEvent,
        time: AnalyticsListener.EventTime,
        config: AudioSink.AudioTrackConfig,
    ) = emit(
        event,
        time,
        TraceField.SAMPLE_RATE to config.sampleRate.toLong(),
        TraceField.ENCODING to config.encoding.toLong(),
        TraceField.BUFFER_BYTES to config.bufferSize.toLong(),
        TraceField.OFFLOAD to if (config.offload) 1L else 0L,
        TraceField.TUNNELING to if (config.tunneling) 1L else 0L,
    )

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) = emit(
        TraceEvent.AUDIO_FORMAT,
        eventTime,
        TraceField.SAMPLE_RATE to format.sampleRate.toLong(),
        TraceField.CHANNELS to format.channelCount.toLong(),
        TraceField.REUSE_RESULT to (decoderReuseEvaluation?.result?.toLong() ?: -1L),
        category = formatTraceCategory(format.sampleMimeType),
    )

    override fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?,
    ) = emit(
        TraceEvent.VIDEO_FORMAT,
        eventTime,
        TraceField.WIDTH to format.width.toLong(),
        TraceField.HEIGHT to format.height.toLong(),
        TraceField.REUSE_RESULT to (decoderReuseEvaluation?.result?.toLong() ?: -1L),
        category = formatTraceCategory(format.sampleMimeType),
    )

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) = emit(TraceEvent.FIRST_VIDEO_FRAME, eventTime, TraceField.FRAME_AT_MS to renderTimeMs)

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) = emit(TraceEvent.DROPPED_VIDEO_FRAMES, eventTime, TraceField.COUNT to droppedFrames.toLong(), TraceField.DURATION_MS to elapsedMs)

    override fun onVideoSizeChanged(
        eventTime: AnalyticsListener.EventTime,
        videoSize: VideoSize,
    ) = emit(TraceEvent.VIDEO_SIZE, eventTime, TraceField.WIDTH to videoSize.width.toLong(), TraceField.HEIGHT to videoSize.height.toLong())

    override fun onSurfaceSizeChanged(
        eventTime: AnalyticsListener.EventTime,
        width: Int,
        height: Int,
    ) = emit(TraceEvent.SURFACE_SIZE, eventTime, TraceField.WIDTH to width.toLong(), TraceField.HEIGHT to height.toLong())

    override fun onPlayerError(
        eventTime: AnalyticsListener.EventTime,
        error: PlaybackException,
    ) = emit(TraceEvent.PLAYER_ERROR, eventTime, TraceField.ERROR_CODE to error.errorCode.toLong())

    override fun onAudioSinkError(
        eventTime: AnalyticsListener.EventTime,
        audioSinkError: Exception,
    ) = emit(TraceEvent.AUDIO_SINK_ERROR, eventTime)

    override fun onAudioCodecError(
        eventTime: AnalyticsListener.EventTime,
        audioCodecError: Exception,
    ) = emit(TraceEvent.AUDIO_CODEC_ERROR, eventTime)
}
