package io.github.aedev.flow.ui.screens.player.state

import io.github.aedev.flow.data.local.VideoQuality
import io.github.aedev.flow.data.model.LiveChatMessage
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.extractor.stream.VideoStream

data class VideoPlayerUiState(
    val cachedVideo: Video? = null,
    val relatedVideos: List<Video> = emptyList(),
    val videoStream: VideoStream? = null,
    val audioStream: AudioStream? = null,
    val availableQualities: List<VideoQuality> = emptyList(),
    val selectedQuality: VideoQuality = VideoQuality.AUTO,
    val subtitlesEnabled: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Optional secondary hint shown below the primary error in the player's error panel. */
    val errorHint: String? = null,
    val savedPosition: Long? = null,
    val isAdaptiveMode: Boolean = false,
    val isSubscribed: Boolean = false,
    val isNotificationsEnabled: Boolean = false,
    val likeState: String? = null,
    val channelSubscriberCount: Long? = null,
    val channelAvatarUrl: String? = null,
    val chapters: List<StreamSegment> = emptyList(),
    /** A livestream that has ended: its date reads "Streamed …" rather than a plain date. */
    val isArchivedLivestream: Boolean = false,
    val autoplayEnabled: Boolean = true,
    val localFilePath: String? = null,
    val localFileVideoId: String? = null,
    val dislikeCount: Long? = null,
    val queueTitle: String? = null,
    val hlsUrl: String? = null,
    val shouldDismissPlayer: Boolean = false,
    val isBackgroundPlaybackMode: Boolean = false,
    val isRestoredSession: Boolean = false,
    val resumedInMiniPlayer: Boolean = false,
    val isUpcoming: Boolean = false,
    val upcomingReleaseTimeMs: Long? = null,
    val isUpcomingReminderSet: Boolean = false,
    /** SponsorBlock segments loaded from local DB for offline playback. Null when streaming online. */
    val offlineSponsorBlockSegments: List<SponsorBlockSegment>? = null,
    val isLive: Boolean = false,
    val isLiveChatAvailable: Boolean = false,
    val liveChatMessages: List<LiveChatMessage> = emptyList(),
    val isLiveChatLoading: Boolean = false,
) {
    /**
     * The state to show while [video] is being armed: its metadata, and every field that describes
     * the video that was playing before it cleared. Sheet flags, queue title and the live-chat
     * transcript are left alone — a caller that owns one of those re-applies it with `copy`.
     */
    fun resetForVideo(video: Video): VideoPlayerUiState =
        copy(
            cachedVideo = video,
            isRestoredSession = false,
            isLoading = true,
            error = null,
            errorHint = null,
            videoStream = null,
            audioStream = null,
            savedPosition = null,
            relatedVideos = emptyList(),
            isSubscribed = false,
            likeState = null,
            hlsUrl = null,
            isLive = false,
            localFilePath = null,
            localFileVideoId = null,
            isUpcoming = false,
            upcomingReleaseTimeMs = null,
        )
}
