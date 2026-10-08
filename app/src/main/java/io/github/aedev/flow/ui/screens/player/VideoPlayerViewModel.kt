package io.github.aedev.flow.ui.screens.player

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.account.AccountPlayHistory
import io.github.aedev.flow.data.comments.VideoCommentSort
import io.github.aedev.flow.data.engagement.FeedInvalidationBus
import io.github.aedev.flow.data.engagement.VideoEngagementUseCase
import io.github.aedev.flow.data.local.*
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.transcript.TranscriptRepository
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.data.video.VideoQueueStore
import io.github.aedev.flow.di.IoDispatcher
import io.github.aedev.flow.di.NetworkIoDispatcher
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.PluginPlaybackResolver
import io.github.aedev.flow.plugin.playback.PluginVideo
import io.github.aedev.flow.ui.screens.player.state.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.*
import javax.inject.Inject

private const val QUEUE_SAVE_DEBOUNCE_MS = 1_000L

/**
 * Owns the player screen's state and the API the UI calls: what plays, what the player reports back,
 * and what the surrounding controllers are armed with.
 *
 * The state has two writers by responsibility: [PlayerSessionController] and this class write what an
 * entry point and the player's own state changes land on, [PlaybackSessionApplier] writes what a
 * resolved load lands on. All hold the one flow constructed here and gate on the same load token.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class VideoPlayerViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val transcriptRepository: TranscriptRepository,
        private val viewHistory: ViewHistory,
        private val engagement: VideoEngagementUseCase,
        private val playlistRepository: io.github.aedev.flow.data.local.PlaylistRepository,
        private val playerPreferences: PlayerPreferences,
        private val videoDownloadManager: VideoDownloadManager,
        private val videoQueueStore: VideoQueueStore,
        private val watchLaterCleanup: WatchLaterCleanup,
        private val offlineSubtitleStore: io.github.aedev.flow.data.video.OfflineSubtitleStore,
        private val sponsorBlockRepository: SponsorBlockRepository,
        private val homeFeedCacheRepository: HomeFeedCacheRepository,
        private val playerManager: EnhancedPlayerManager,
        private val playbackResolver: PluginPlaybackResolver,
        private val pluginVideo: PluginVideo,
        accountPlayHistory: AccountPlayHistory,
        notesRepository: io.github.aedev.flow.data.notes.NotesRepository,
        private val videoStats: io.github.aedev.flow.data.stats.VideoStatsRecorder,
        @NetworkIoDispatcher private val networkDispatcher: CoroutineDispatcher,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(VideoPlayerUiState())
        val uiState: StateFlow<VideoPlayerUiState> = _uiState.asStateFlow()

        private val notes = PlayerNotes(notesRepository, playerPreferences, viewModelScope)

        private val loads: PlaybackLoadJobs = PlaybackLoadJobs(viewModelScope, networkDispatcher, onCancel = { secondaryMetadata.cancel() })

        val videoNotesEnabled: StateFlow<Boolean> = notes.enabled

        private val collaborators: PlayerCollaborators =
            PlayerCollaborators(
                context = context,
                transcriptRepository = transcriptRepository,
                viewHistory = viewHistory,
                engagement = engagement,
                playerPreferences = playerPreferences,
                videoDownloadManager = videoDownloadManager,
                offlineSubtitleStore = offlineSubtitleStore,
                sponsorBlockRepository = sponsorBlockRepository,
                pluginVideo = pluginVideo,
                accountPlayHistory = accountPlayHistory,
                homeFeedCacheRepository = homeFeedCacheRepository,
                playerManager = playerManager,
                videoStats = videoStats,
                uiState = _uiState,
                scope = viewModelScope,
                networkDispatcher = networkDispatcher,
                isLoadCurrent = loads::isCurrent,
                currentLoadToken = { loads.token },
                shortsEnabled = { shortsContentEnabled },
            )

        private val comments = collaborators.comments
        private val secondaryMetadata = collaborators.secondaryMetadata
        private val watchSessions = collaborators.watchSessions
        private val liveChat = collaborators.liveChat
        private val engagementState = collaborators.engagementState
        private val upcomingPremiere = collaborators.upcomingPremiere

        val commentsState: StateFlow<List<Comment>> = comments.comments
        val isLoadingComments: StateFlow<Boolean> = comments.isLoading
        val hasMoreComments: StateFlow<Boolean> = comments.hasMore

        private var clearedUnplayableVideoId: String? = null

        private val recovery: PlaybackRecoveryController =
            PlaybackRecoveryController(
                context = context,
                uiState = _uiState,
                playerManager = playerManager,
                playerPreferences = playerPreferences,
                watchSessions = watchSessions,
                scope = viewModelScope,
                isLoadInFlight = { loads.isInFlight },
                cancelLoad = { loads.cancel(invalidateToken = true) },
                reloadStreams = { videoId, resumePositionMs ->
                    // The plugin is told which URL failed, so it hands back a different one.
                    playerManager.lastServerAbrFailure?.let { failure ->
                        pluginVideo.failed(videoId, failure.url, failure.status, failure.reloadPlaybackContext, failure.serverAbrFailure)
                    } ?: playerManager.lastStreamHttpFailure
                        ?.let { (url, status) -> pluginVideo.failed(videoId, url, status) }
                        ?: pluginVideo.forget(videoId)
                    loadVideoInfo(videoId, forceRefresh = true, resumePositionOverrideMs = resumePositionMs)
                },
            )

        private val settings: PlaybackSettingsController =
            PlaybackSettingsController(
                uiState = _uiState,
                playerManager = playerManager,
                playerPreferences = playerPreferences,
                scope = viewModelScope,
            )

        private val presence: PlaybackPresenceController =
            PlaybackPresenceController(
                uiState = _uiState,
                playerManager = playerManager,
                playerPreferences = playerPreferences,
                viewHistory = viewHistory,
                scope = viewModelScope,
                ioDispatcher = ioDispatcher,
                resumePlayback = ::playVideo,
                savedQueue = videoQueueStore::load,
                resumeQueue = { videos, index, title -> playPlaylist(videos, index, title) },
            )

        private val session: PlayerSessionController =
            PlayerSessionController(
                context = context,
                uiState = _uiState,
                scope = viewModelScope,
                playerManager = playerManager,
                viewHistory = viewHistory,
                resolver = playbackResolver,
                pluginVideo = pluginVideo,
                loads = loads,
                collaborators = collaborators,
                recovery = recovery,
                presence = presence,
                notes = notes,
                shortsEnabled = { shortsContentEnabled },
            )

        val canGoPrevious: StateFlow<Boolean> = session.canGoPrevious

        private fun isLocalMediaId(id: String?): Boolean = LocalMediaIds.isLocal(id)

        /** Arms the live chat for [videoId]; the drip loop itself waits for a visible panel. */
        fun maybeStartLiveChat(videoId: String) = liveChat.start(videoId)

        fun stopLiveChat() = liveChat.stop()

        fun setLiveChatPanelVisible(visible: Boolean) = liveChat.setPanelVisible(visible)

        override fun onCleared() {
            super.onCleared()
            watchSessions.finalizeActiveSession()
            stopLiveChat()
        }

        /**
         * Detect whether the device is currently on Wi-Fi.
         * Used to select the correct quality preference (Wi-Fi vs cellular).
         */

        @Volatile
        private var shortsContentEnabled: Boolean = true

        init {
            // The first value is the empty queue of a fresh process; saving it would erase the one to restore.
            combine(playerManager.queueVideos, playerManager.currentQueueIndexState, ::Pair)
                .drop(1)
                .debounce(QUEUE_SAVE_DEBOUNCE_MS)
                .onEach { (videos, index) -> videoQueueStore.save(videos, index, playerManager.playerState.value.queueTitle) }
                .launchIn(viewModelScope)

            playerPreferences.shortsContentEnabled
                .onEach { shortsContentEnabled = it }
                .launchIn(viewModelScope)

            combine(liveChat.messages, liveChat.isLoading, liveChat.isAvailable, ::Triple)
                .onEach { (messages, isLoading, isAvailable) ->
                    _uiState.update { it.applyLiveChat(messages, isLoading, isAvailable) }
                }.launchIn(viewModelScope)

            recovery.collectPlayerEvents()

            playerManager.playerState
                .onEach(::onPlayerStateChanged)
                .launchIn(viewModelScope)

            playerManager.playbackCompletedEvent
                .onEach { completion ->
                    watchSessions.markCompleted(completion)
                    watchLaterCleanup.onFinished(completion.videoId)
                }.launchIn(viewModelScope)

            presence.restoreLastWatchedSession()

            FeedInvalidationBus.events
                .onEach { event -> _uiState.update { it.applyFeedInvalidation(event) } }
                .launchIn(viewModelScope)

            settings.collectAutoplayPreference()
            upcomingPremiere.collectReminderState()
        }

        private suspend fun onPlayerStateChanged(playerState: EnhancedPlayerState) {
            _uiState.update { it.mirrorPlayerState(playerState) }
            liveChat.setPlaying(playerState.playWhenReady)

            // A video that prepares successfully is not unplayable, whatever a past failure said.
            playerState.currentVideoId
                ?.takeIf { playerState.isPrepared && it != clearedUnplayableVideoId }
                ?.let { preparedVideoId ->
                    clearedUnplayableVideoId = preparedVideoId
                    playerPreferences.clearVideoUnplayable(preparedVideoId)
                }

            session.followPlayer(playerState)
        }

        fun resumeRestoredSession(stayMini: Boolean = false) = presence.resumeRestoredSession(stayMini)

        fun dismissContinueWatching() = presence.dismissContinueWatching()

        fun ensureNotificationServiceRunning() = presence.ensureNotificationServiceRunning()

        fun clearResumedInMiniPlayer() = presence.clearResumedInMiniPlayer()

        fun syncWithCurrentPlayerVideo(video: Video) = session.syncWithCurrentPlayerVideo(video)

        /** Shows [video]'s metadata at once and starts loading its streams. */
        fun playVideo(video: Video) = session.playVideo(video)

        fun playLocalVideo(
            video: Video,
            contentUri: String,
        ) = session.playLocalVideo(video, contentUri)

        fun clearVideo() = session.clearVideo()

        fun startBackgroundPlayback() = presence.startBackgroundPlayback()

        fun resetDismissState() = presence.resetDismissState()

        fun showVideoPlayer() = presence.showVideoPlayer()

        fun retryLoadVideo() = session.retryLoadVideo()

        fun ensurePlaybackPrepared(videoId: String) = session.ensurePlaybackPrepared(videoId)

        /** [shuffle] turns the queue's shuffle on or off for this list; null keeps the current setting. */
        fun playPlaylist(
            videos: List<Video>,
            startIndex: Int,
            title: String? = null,
            shuffle: Boolean? = null,
        ) = session.playPlaylist(videos, startIndex, title, shuffle)

        fun playNext() = session.playNext()

        fun playPrevious() = session.playPrevious()

        /** Resolves [videoId] through the video plugin; see [PlayerSessionController.loadVideoInfo]. */
        fun loadVideoInfo(
            videoId: String,
            forceRefresh: Boolean = false,
            resumePositionOverrideMs: Long? = null,
        ) = session.loadVideoInfo(videoId, forceRefresh, resumePositionOverrideMs)

        fun switchQuality(quality: VideoQuality) = settings.switchQuality(quality)

        fun savePlaybackPosition(
            videoId: String,
            position: Long,
            duration: Long,
            title: String,
            thumbnailUrl: String,
            channelName: String = "",
            channelId: String = "",
            isShort: Boolean = false,
        ) {
            if (!positionBelongsTo(videoId, playerManager.playerState.value.currentVideoId)) return
            watchSessions.savePlaybackPosition(
                videoId = videoId,
                positionMs = position,
                durationMs = duration,
                title = title,
                thumbnailUrl = thumbnailUrl,
                channelName = channelName,
                channelId = channelId,
                isShort = isShort,
                isLocal = isLocalMediaId(videoId),
            )
            viewModelScope.launch { watchLaterCleanup.onProgress(videoId, position, duration) }
        }

        /** The app is going to the background: the recap gets the open session's progress so far. */
        fun checkpointWatchSession() = watchSessions.checkpoint()

        /** Live streams keep no history row; their watching time goes to the recap only. */
        fun trackLivePlayback(
            video: Video,
            position: Long,
        ) = watchSessions.trackLive(video, position)

        fun toggleSubscription(
            channelId: String,
            channelName: String,
            channelThumbnail: String,
        ) = engagementState.toggleSubscription(channelId, channelName, channelThumbnail)

        fun setNotificationEnabled(
            channelId: String,
            enabled: Boolean,
        ) = engagementState.setNotificationEnabled(channelId, enabled)

        fun likeVideo(
            videoId: String,
            title: String,
            thumbnail: String,
            channelName: String,
            channelId: String = "",
        ) = engagementState.like(videoId, title, thumbnail, channelName, channelId)

        fun dislikeVideo(videoId: String) = engagementState.dislike(videoId)

        fun removeLikeState(videoId: String) = engagementState.removeLike(videoId)

        fun toggleSubtitles(enabled: Boolean) = settings.setSubtitlesEnabled(enabled)

        fun toggleAutoplay(enabled: Boolean) = settings.toggleAutoplay(enabled)

        fun toggleLoop(enabled: Boolean) = settings.toggleLoop(enabled)

        fun loadComments(videoId: String) {
            if (isLocalMediaId(videoId)) {
                comments.clear()
                return
            }
            comments.load(videoId)
        }

        fun loadMoreComments(videoId: String) = comments.loadMore(videoId)

        fun selectCommentSort(
            videoId: String,
            sort: VideoCommentSort,
        ) = comments.selectSort(videoId, sort)

        fun loadCommentReplies(comment: Comment) {
            val videoId = _uiState.value.cachedVideo?.id ?: return
            comments.loadReplies(videoId, comment)
        }

        fun loadMoreCommentReplies(comment: Comment) {
            val videoId = _uiState.value.cachedVideo?.id ?: return
            comments.loadMoreReplies(videoId, comment)
        }

        fun toggleSkipSilence(isEnabled: Boolean) = settings.toggleSkipSilence(isEnabled)

        fun toggleStableVolume(isEnabled: Boolean) = settings.toggleStableVolume(isEnabled)
    }
