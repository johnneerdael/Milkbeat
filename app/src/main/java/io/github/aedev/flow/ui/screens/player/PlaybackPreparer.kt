package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.PlaybackResumePolicy
import io.github.aedev.flow.player.StreamRequestHeaders
import io.github.aedev.flow.player.stream.VideoCodecUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Every hand-off from the player screen to [EnhancedPlayerManager]: arming the session, choosing the
 * position playback resumes from, pushing the streams, restoring the remembered speed and starting
 * playback — in that order, on the main thread.
 *
 * It owns no screen state and writes none: callers pass plain values and a currency check, so the
 * call sequence a load produces can be asserted against one mocked manager.
 *
 * The manager itself is the injected instance the ViewModel already holds; this class never creates,
 * looks up or releases one.
 */
internal class PlaybackPreparer(
    private val context: Context,
    private val playerManager: EnhancedPlayerManager,
    private val playerPreferences: PlayerPreferences,
    private val offlineSubtitleStore: OfflineSubtitleStore,
) {
    /** Arms the player and the media notification for [videoId] before any streams are handed over. */
    suspend fun beginSession(
        videoId: String,
        title: String,
        channel: String,
        thumbnail: String,
    ) = withContext(Dispatchers.Main) {
        playerManager.initialize(context)
        playerManager.startBackgroundService(videoId = videoId, title = title, channel = channel, thumbnail = thumbnail)
    }

    /** Publishes what plays next when the current video ends, and reports whether autoplay is on. */
    suspend fun applyAutoplayCandidates(
        videoId: String,
        videos: List<Video>,
    ): Boolean =
        withContext(Dispatchers.Main) {
            val autoplay = playerPreferences.autoplayEnabled.first()
            playerManager.setAutoplayCandidates(sourceVideoId = videoId, videos = videos, enabled = autoplay)
            autoplay
        }

    /**
     * Returns false when playback was not started — the load is no longer current, or the player
     * already owns this media item — so the caller can skip the work that follows a real start.
     */
    suspend fun prepareLiveStreams(
        videoId: String,
        hlsUrl: String?,
        dashManifestUrl: String?,
        subtitles: List<SubtitlesStream>,
        isCurrent: () -> Boolean,
        requestHeaders: StreamRequestHeaders = StreamRequestHeaders.NONE,
    ): Boolean =
        withContext(Dispatchers.Main) {
            if (!isCurrent()) return@withContext false
            if (playerManager.isPreparedForPlayback(videoId)) return@withContext false

            playerManager.setStreams(
                videoId = videoId,
                videoStream = null,
                audioStream = null,
                videoStreams = emptyList(),
                audioStreams = emptyList(),
                subtitles = subtitles,
                durationSeconds = 0L,
                dashManifestUrl = dashManifestUrl,
                hlsUrl = hlsUrl,
                streamType = StreamType.LIVE_STREAM,
                startPosition = 0L,
                preferredVideoCodec = VideoCodecUtils.NO_PREFERENCE,
                preferredLiveQualityHeight = VideoQuality.AUTO.height,
                requestHeaders = requestHeaders,
            )
            applyRememberedPlaybackSpeed(isLive = true)

            if (!isCurrent()) return@withContext false
            playerManager.play()
            true
        }

    suspend fun prepareVodStreams(
        videoId: String,
        videoStream: VideoStream?,
        audioStream: AudioStream?,
        videoStreams: List<VideoStream>,
        audioStreams: List<AudioStream>,
        subtitles: List<SubtitlesStream>,
        durationSeconds: Long,
        savedPositionMs: Long,
        resumeOverrideRequested: Boolean,
        isAdaptiveMode: Boolean,
        preferredVideoCodec: String,
        preferredLiveQualityHeight: Int,
        isCurrent: () -> Boolean,
        requestHeaders: StreamRequestHeaders = StreamRequestHeaders.NONE,
        skipSegments: List<SponsorBlockSegment>? = null,
    ) = withContext(Dispatchers.Main) {
        if (!isCurrent()) return@withContext
        if (playerManager.isPreparedForPlayback(videoId)) return@withContext

        val resumePosition =
            PlaybackResumePolicy.resolveStartPosition(
                savedPosition = savedPositionMs,
                durationMs = durationSeconds * 1000L,
                resumeAllowed = resumeOverrideRequested || !playerManager.isReachedByQueueAdvance(videoId),
            )

        playerManager.setStreams(
            videoId = videoId,
            videoStream = if (isAdaptiveMode) null else videoStream,
            audioStream = audioStream,
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            subtitles = subtitles,
            durationSeconds = durationSeconds,
            dashManifestUrl = null,
            hlsUrl = null,
            streamType = StreamType.VIDEO_STREAM,
            startPosition = resumePosition,
            preferredVideoCodec = preferredVideoCodec,
            preferredLiveQualityHeight = preferredLiveQualityHeight,
            requestHeaders = requestHeaders,
            skipSegments = skipSegments,
        )
        applyRememberedPlaybackSpeed(isLive = false)

        if (!isCurrent()) return@withContext
        playerManager.play()
    }

    suspend fun prepareLocalMedia(
        videoId: String,
        localFilePath: String,
        offlineSegments: List<SponsorBlockSegment>?,
        savedPosition: Long,
        durationMs: Long,
        subtitles: List<SubtitlesStream>,
        isCurrent: () -> Boolean,
    ) = withContext(Dispatchers.Main) {
        if (!isCurrent()) return@withContext
        if (playerManager.isPreparedForPlayback(videoId)) return@withContext

        playerManager.initialize(context)
        val startPosition =
            PlaybackResumePolicy.resolveStartPosition(
                savedPosition = savedPosition,
                durationMs = durationMs,
                resumeAllowed = !playerManager.isReachedByQueueAdvance(videoId),
            )
        playerManager.playLocalFile(
            videoId = videoId,
            filePath = localFilePath,
            savedSegments = offlineSegments,
            preservePosition = startPosition.takeIf { it > 0L },
            subtitles = subtitles,
        )
        applyRememberedPlaybackSpeed(isLive = false)

        if (!isCurrent()) return@withContext
        playerManager.play()
    }

    private suspend fun applyRememberedPlaybackSpeed(isLive: Boolean) {
        if (isLive) {
            playerManager.setPlaybackSpeed(1.0f)
            return
        }
        if (playerPreferences.rememberPlaybackSpeed.first()) {
            playerManager.setPlaybackSpeed(playerPreferences.playbackSpeed.first())
        }
    }

    private companion object {
        const val TAG = "PlaybackPreparer"
    }
}
