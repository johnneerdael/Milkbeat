package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.local.ChannelSubscription
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.LikedVideosRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.PlaylistRepository
import io.github.aedev.flow.data.local.SubscriptionRepository
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.local.entity.WatchHistoryEntity
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.transcript.TranscriptRepository
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.error.PlayerDiagnostics
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.CaptionTrackResolver
import io.github.aedev.flow.player.stream.PluginPlaybackResolver
import io.github.aedev.flow.plugin.playback.PluginVideo
import io.github.aedev.flow.utils.NetworkState
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestDispatcher

/**
 * Characterisation harness for [VideoPlayerViewModel]: every constructor dependency is a relaxed
 * mock, every static object the ViewModel reaches is a mockk object spy, and every flow the init
 * block collects is backed by a real [MutableStateFlow]/[MutableSharedFlow] so tests can drive it.
 *
 * The defaults describe a healthy, online, empty device: no downloads, no restored session, no
 * music playing, Auto quality. Individual tests override what they need.
 */
internal class VideoPlayerViewModelHarness(
    private val testDispatcher: TestDispatcher,
) {
    val context: Context = mockk(relaxed = true)
    val transcriptRepository: TranscriptRepository = mockk(relaxed = true)
    val viewHistory: ViewHistory = mockk(relaxed = true)
    val subscriptionRepository: SubscriptionRepository = mockk(relaxed = true)
    val likedVideosRepository: LikedVideosRepository = mockk(relaxed = true)
    val playlistRepository: PlaylistRepository = mockk(relaxed = true)
    val playerPreferences: PlayerPreferences = mockk(relaxed = true)
    val videoDownloadManager: VideoDownloadManager = mockk(relaxed = true)
    val offlineSubtitleStore: OfflineSubtitleStore = mockk(relaxed = true)
    val sponsorBlockRepository: SponsorBlockRepository = mockk(relaxed = true)
    val pluginVideo: PluginVideo = mockk(relaxed = true)
    val homeFeedCacheRepository: HomeFeedCacheRepository = mockk(relaxed = true)
    val playerManager: EnhancedPlayerManager = mockk(relaxed = true)
    val videoStats: io.github.aedev.flow.data.stats.VideoStatsRecorder = mockk(relaxed = true)

    /**
     * The real use case over the mocked repositories: every engagement assertion in the suite is
     * written against [subscriptionRepository]/[likedVideosRepository], so the seam under test
     * stays the repository call, not the use case.
     */
    val engagement: VideoEngagementUseCase by lazy {
        VideoEngagementUseCase(
            subscriptionRepository = subscriptionRepository,
            likedVideosRepository = likedVideosRepository,
            videoStats = videoStats,
        )
    }

    val playerState = MutableStateFlow(EnhancedPlayerState())
    val streamExpiredEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val playbackAbandonedEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val queueVideos = MutableStateFlow<List<Video>>(emptyList())
    val videoQueueStore = mockk<io.github.aedev.flow.data.video.VideoQueueStore>(relaxed = true)
    val musicCurrentTrack = MutableStateFlow<MusicTrack?>(null)
    val autoplayEnabled = MutableStateFlow(true)
    val continueWatchingEnabled = MutableStateFlow(true)
    val downloadedVideos = MutableStateFlow<List<DownloadedVideo>>(emptyList())
    val isSubscribed = MutableStateFlow(false)
    val subscription = MutableStateFlow<ChannelSubscription?>(null)
    val likeState = MutableStateFlow<String?>(null)

    init {
        mockkObject(EnhancedPlayerManager.Companion)
        every { EnhancedPlayerManager.getInstance() } returns playerManager
        every { playerManager.playerState } returns playerState
        every { playerManager.streamExpiredEvent } returns streamExpiredEvent
        every { playerManager.playbackAbandonedEvent } returns playbackAbandonedEvent
        every { playerManager.queueVideos } returns queueVideos
        every { playerManager.currentQueueIndexState } returns MutableStateFlow(-1)
        every { playerManager.getPlayer() } returns null
        every { playerManager.isPreparedForPlayback(any()) } returns false
        every { playerManager.isReachedByQueueAdvance(any()) } returns false
        every { playerManager.lastStreamHttpFailure } returns null
        every { playerManager.lastServerAbrFailure } returns null

        // No video plugin answers: every load ends on the generic error, as a failed extraction did.
        coEvery { pluginVideo.resolve(any()) } returns Result.failure(IllegalStateException())
        coEvery { pluginVideo.related(any()) } returns emptyList()

        // Reset the real singleton before spying it so the reset is not a recorded call.
        GlobalPlayerState.setCurrentVideo(null)
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
        mockkObject(GlobalPlayerState)

        mockkObject(EnhancedMusicPlayerManager)
        every { EnhancedMusicPlayerManager.currentTrack } returns musicCurrentTrack
        every { EnhancedMusicPlayerManager.stop() } just Runs
        every { EnhancedMusicPlayerManager.clearCurrentTrack() } just Runs

        mockkObject(NetworkState)
        every { NetworkState.isOnline(any()) } returns true

        mockkObject(PlayerDiagnostics)
        every { PlayerDiagnostics.logWarning(any(), any()) } just Runs

        every { context.applicationContext } returns context
        every { context.getString(any()) } answers { "res:${firstArg<Int>()}" }
        every { context.getString(any(), *anyVararg()) } answers { "res:${firstArg<Int>()}" }

        every { playerPreferences.shortsContentEnabled } returns flowOf(true)
        every { playerPreferences.effectiveVideoNotesEnabled } returns flowOf(false)
        every { playerPreferences.miniPlayerContinueWatchingEnabled } returns continueWatchingEnabled
        every { playerPreferences.autoplayEnabled } returns autoplayEnabled
        every { playerPreferences.upcomingVideoReminderIds } returns flowOf(emptySet())
        every { playerPreferences.preferredAudioLanguage } returns flowOf("original")
        every { playerPreferences.preferredSubtitleLanguage } returns flowOf(CaptionTrackResolver.NO_PREFERRED_LANGUAGE)
        every { playerPreferences.rememberPlaybackSpeed } returns flowOf(false)
        every { playerPreferences.playbackSpeed } returns flowOf(1f)

        coEvery { viewHistory.getLatestUnfinishedVideo() } returns null
        every { viewHistory.getPlaybackPosition(any()) } returns flowOf(0L)
        coEvery { viewHistory.getSavedPosition(any()) } returns 0L

        every { videoDownloadManager.downloadedVideos } returns downloadedVideos
        coEvery { videoDownloadManager.getSponsorBlockData(any()) } returns null
        coEvery { offlineSubtitleStore.load(any()) } returns emptyList()

        every { subscriptionRepository.isSubscribed(any()) } returns isSubscribed
        every { subscriptionRepository.getSubscription(any()) } returns subscription
        every { likedVideosRepository.getLikeState(any()) } returns likeState
    }

    fun createViewModel(): VideoPlayerViewModel =
        VideoPlayerViewModel(
            context = context,
            transcriptRepository = transcriptRepository,
            viewHistory = viewHistory,
            engagement = engagement,
            playlistRepository = playlistRepository,
            playerPreferences = playerPreferences,
            videoDownloadManager = videoDownloadManager,
            videoQueueStore = videoQueueStore,
            watchLaterCleanup = mockk(relaxed = true),
            offlineSubtitleStore = offlineSubtitleStore,
            sponsorBlockRepository = sponsorBlockRepository,
            homeFeedCacheRepository = homeFeedCacheRepository,
            playerManager = playerManager,
            playbackResolver =
                PluginPlaybackResolver(
                    pluginVideo = pluginVideo,
                    videoDownloadManager = videoDownloadManager,
                    sponsorBlockRepository = sponsorBlockRepository,
                    ioDispatcher = testDispatcher,
                ),
            pluginVideo = pluginVideo,
            accountPlayHistory = mockk(relaxed = true),
            notesRepository = mockk(relaxed = true),
            videoStats = videoStats,
            networkDispatcher = testDispatcher,
            ioDispatcher = testDispatcher,
        )

    fun close() {
        unmockkAll()
    }

    companion object {
        fun video(
            id: String,
            title: String = "Title $id",
            channelId: String = "channel_$id",
            isUpcoming: Boolean = false,
        ): Video =
            Video(
                id = id,
                title = title,
                channelName = "Channel $id",
                channelId = channelId,
                thumbnailUrl = "https://example.invalid/$id.jpg",
                duration = 120,
                viewCount = 1L,
                uploadDate = "2026-01-01",
                channelThumbnailUrl = "https://example.invalid/$channelId-avatar.jpg",
                isUpcoming = isUpcoming,
            )

        fun historyEntity(
            id: String,
            positionMs: Long = 30_000L,
            durationMs: Long = 120_000L,
        ): WatchHistoryEntity =
            WatchHistoryEntity(
                videoId = id,
                position = positionMs,
                duration = durationMs,
                timestamp = 1_000L,
                title = "Title $id",
                thumbnailUrl = "https://example.invalid/$id.jpg",
                channelName = "Channel $id",
                channelId = "channel_$id",
                isMusic = false,
            )
    }
}
