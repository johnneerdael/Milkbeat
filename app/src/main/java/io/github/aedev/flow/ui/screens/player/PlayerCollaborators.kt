package io.github.aedev.flow.ui.screens.player

import android.content.Context
import io.github.aedev.flow.data.account.AccountPlayHistory
import io.github.aedev.flow.data.comments.CommentsPager
import io.github.aedev.flow.data.comments.CommentsPlaybackState
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.local.HomeFeedCacheRepository
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.stats.VideoStatsRecorder
import io.github.aedev.flow.data.transcript.TranscriptRepository
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.plugin.playback.PluginCommentsSource
import io.github.aedev.flow.plugin.playback.PluginVideo
import io.github.aedev.flow.plugin.playback.PluginVideoPages.toAppMessage
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.richVideoFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Builds the collaborators one player screen runs on, in the order they depend on each other, and
 * holds them for the ViewModel that owns their lifetime.
 *
 * Construction only: nothing here reads, fetches, collects or prepares anything. The scope, the
 * state flow and the player manager are the ViewModel's own — this class creates no second one of
 * anything, and every collaborator it builds is built exactly once.
 */
internal class PlayerCollaborators(
    context: Context,
    transcriptRepository: TranscriptRepository,
    viewHistory: ViewHistory,
    engagement: VideoEngagementUseCase,
    playerPreferences: PlayerPreferences,
    videoDownloadManager: VideoDownloadManager,
    offlineSubtitleStore: OfflineSubtitleStore,
    sponsorBlockRepository: SponsorBlockRepository,
    pluginVideo: PluginVideo,
    accountPlayHistory: AccountPlayHistory,
    homeFeedCacheRepository: HomeFeedCacheRepository,
    playerManager: EnhancedPlayerManager,
    private val videoStats: VideoStatsRecorder,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    scope: CoroutineScope,
    networkDispatcher: CoroutineDispatcher,
    isLoadCurrent: (Long) -> Boolean,
    currentLoadToken: () -> Long,
    shortsEnabled: () -> Boolean,
) {
    val comments =
        CommentsPager(
            source = PluginCommentsSource(pluginVideo),
            scope = scope,
            playbackState =
                uiState.map {
                    CommentsPlaybackState(
                        isPlaybackLoading = it.isLoading,
                        currentVideoId = it.cachedVideo?.id,
                    )
                },
            isCurrentVideo = { videoId -> uiState.value.cachedVideo?.id == videoId },
        )

    val transcripts =
        VideoTranscriptLoader(
            repository = transcriptRepository,
            scope = scope,
            networkDispatcher = networkDispatcher,
        )

    private val playbackPreparer =
        PlaybackPreparer(
            context = context,
            playerManager = playerManager,
            playerPreferences = playerPreferences,
            offlineSubtitleStore = offlineSubtitleStore,
        )

    val secondaryMetadata =
        PlayerSecondaryMetadataLoader(
            playerManager = playerManager,
            scope = scope,
            networkDispatcher = networkDispatcher,
            currentState = { uiState.value },
            relatedVideosFor = ::relatedVideosFor,
            shortsEnabled = shortsEnabled,
            isPlaybackCurrent = isLoadCurrent,
            onResult = { result -> sessionApplier.applySecondary(result) },
            fetchRelated = pluginVideo::related,
        )

    val watchSessions =
        WatchSessionTracker(
            viewHistory = viewHistory,
            fetchRelated = pluginVideo::related,
            homeFeedCacheRepository = homeFeedCacheRepository,
            videoStats = videoStats,
            scope = scope,
            networkDispatcher = networkDispatcher,
            shortsEnabled = shortsEnabled,
            relatedVideosFor = ::relatedVideosFor,
            richVideoFor = { videoId -> uiState.value.richVideoFor(videoId) },
            onViewFinished = accountPlayHistory::onWatched,
        )

    val liveChat =
        LiveChatController(
            fetch = { videoId, cursor ->
                pluginVideo.liveChat(videoId, cursor).getOrNull()?.let { batch ->
                    LiveChatPoll(batch.messages.map { it.toAppMessage() }, batch.next, batch.pollAfterMs)
                }
            },
            scope = scope,
            dispatcher = networkDispatcher,
        )

    val engagementState =
        PlayerEngagementController(
            engagement = engagement,
            scope = scope,
            state = uiState,
            richVideoFor = { videoId -> uiState.value.richVideoFor(videoId) },
        )

    val upcomingPremiere =
        UpcomingPremiereController(
            context = context,
            uiState = uiState,
            playerPreferences = playerPreferences,
            scope = scope,
            isLoadCurrent = isLoadCurrent,
            // A countdown the video's own metadata enters skips the load, so it arms what a load would.
            armMetadata = { videoId ->
                sessionApplier.armCountdownMetadata(LoadContext(videoId, currentLoadToken()), emptyList())
            },
        )

    private val pluginPlayback =
        PluginPlaybackApplier(
            context = context,
            uiState = uiState,
            isLoadCurrent = isLoadCurrent,
            playbackPreparer = playbackPreparer,
            secondaryMetadata = secondaryMetadata,
            liveChat = liveChat,
            viewHistory = viewHistory,
            playerPreferences = playerPreferences,
        )

    val sessionApplier: PlaybackSessionApplier =
        PlaybackSessionApplier(
            context = context,
            uiState = uiState,
            isLoadCurrent = isLoadCurrent,
            playbackPreparer = playbackPreparer,
            pluginPlayback = pluginPlayback,
            secondaryMetadata = secondaryMetadata,
            viewHistory = viewHistory,
            playerPreferences = playerPreferences,
            sponsorBlockRepository = sponsorBlockRepository,
            videoDownloadManager = videoDownloadManager,
            offlineSubtitleStore = offlineSubtitleStore,
            playerManager = playerManager,
            scope = scope,
            networkDispatcher = networkDispatcher,
            enterUpcoming = upcomingPremiere::enterCountdown,
        )

    private fun relatedVideosFor(videoId: String): List<Video> =
        uiState.value
            .takeIf { it.cachedVideo?.id == videoId }
            ?.relatedVideos
            .orEmpty()
}
