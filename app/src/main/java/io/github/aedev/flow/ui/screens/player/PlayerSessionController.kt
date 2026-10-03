package io.github.aedev.flow.ui.screens.player

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.player.stream.PlaybackResolutionRequest
import io.github.aedev.flow.player.stream.PluginPlaybackResolver
import io.github.aedev.flow.plugin.playback.PluginVideo
import io.github.aedev.flow.ui.screens.player.state.PlayerNavigationHistory
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.beginLoadFor
import io.github.aedev.flow.ui.screens.player.state.blankVideo
import io.github.aedev.flow.ui.screens.player.state.blocksLatePrepare
import io.github.aedev.flow.ui.screens.player.state.clearedForNoVideo
import io.github.aedev.flow.ui.screens.player.state.foreignVideoIdNeedingLoad
import io.github.aedev.flow.ui.screens.player.state.holdsVideo
import io.github.aedev.flow.ui.screens.player.state.loadSkipReason
import io.github.aedev.flow.ui.screens.player.state.shouldReopenInsteadOfPlaying
import io.github.aedev.flow.ui.screens.player.state.startLocalPlaybackOf
import io.github.aedev.flow.ui.screens.player.state.startPlaybackOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Every session entry point the player screen offers: what plays, from where, and what happens to
 * the video that played before. Loads resolve through the video plugin and land through the
 * collaborators' [PlaybackSessionApplier]; this class decides when a load starts and what it replaces.
 *
 * It writes the ViewModel's one state flow and holds no scope, player or collaborator of its own.
 */
internal class PlayerSessionController(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val scope: CoroutineScope,
    private val playerManager: EnhancedPlayerManager,
    private val viewHistory: ViewHistory,
    private val resolver: PluginPlaybackResolver,
    private val pluginVideo: PluginVideo,
    private val loads: PlaybackLoadJobs,
    private val collaborators: PlayerCollaborators,
    private val recovery: PlaybackRecoveryController,
    private val presence: PlaybackPresenceController,
    private val notes: PlayerNotes,
    private val shortsEnabled: () -> Boolean,
) {
    private val sessionApplier = collaborators.sessionApplier
    private val watchSessions = collaborators.watchSessions
    private val upcomingPremiere = collaborators.upcomingPremiere

    private val navigationHistory = PlayerNavigationHistory()
    private val _canGoPrevious = MutableStateFlow(false)
    val canGoPrevious: StateFlow<Boolean> = _canGoPrevious.asStateFlow()

    /** The player moved to a video the screen does not hold, such as a queue advance: load it. */
    fun followPlayer(playerState: EnhancedPlayerState) {
        val videoId = uiState.value.foreignVideoIdNeedingLoad(playerState) ?: return
        GlobalPlayerState.currentVideo.value?.takeIf { it.id == videoId }?.let { currentVideo ->
            uiState.update { it.resetForVideo(currentVideo) }
            presence.armNotificationFor(currentVideo)
            watchSessions.saveHistoryEntry(currentVideo)
        }
        loadVideoInfo(videoId, forceRefresh = true)
    }

    fun syncWithCurrentPlayerVideo(video: Video) {
        val state = uiState.value
        val alreadySynced =
            state.cachedVideo?.id == video.id &&
                (state.isLoading || state.isLive || !state.hlsUrl.isNullOrEmpty() || state.localFileVideoId == video.id)
        if (alreadySynced) return

        if (upcomingPremiere.applyCountdown(video)) {
            return
        }

        uiState.update { it.resetForVideo(video) }
        loadVideoInfo(video.id, forceRefresh = true)
    }

    /** Shows [video]'s metadata at once and starts loading its streams. */
    fun playVideo(video: Video) {
        if (uiState.value.shouldReopenInsteadOfPlaying(video.id, playerManager.playerState.value, isMiniPlayerCollapsed = false)) {
            presence.showVideoPlayer()
            return
        }

        loads.next()
        takeOverPlayback()

        uiState.value = uiState.value.startPlaybackOf(video)
        GlobalPlayerState.setCurrentVideo(video)
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
        watchSessions.saveHistoryEntry(video)
        presence.armNotificationFor(video)
        if (upcomingPremiere.applyCountdown(video)) {
            return
        }
        loadVideoInfo(video.id, forceRefresh = true)
    }

    fun playLocalVideo(
        video: Video,
        contentUri: String,
    ) {
        takeOverPlayback()
        prepareDeviceFile(video, contentUri)
    }

    /** Plays a file on the device, keeping whatever queue it belongs to. */
    private fun prepareDeviceFile(
        video: Video,
        contentUri: String,
    ) {
        val loadToken = loads.next()
        uiState.value = uiState.value.startLocalPlaybackOf(video, contentUri)
        GlobalPlayerState.setCurrentVideo(video)
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)
        presence.armNotificationFor(video)

        scope.launch {
            sessionApplier.prepareLocalMedia(
                load = LoadContext(video.id, loadToken),
                localFilePath = contentUri,
                offlineSegments = null,
                savedPosition = runCatching { viewHistory.getSavedPosition(video.id) }.getOrDefault(0L),
            )
        }
    }

    /** Drops the load, the queue and the music player so this screen owns playback outright. */
    private fun takeOverPlayback() {
        loads.cancel()
        recovery.onPlaybackRequested()
        playerManager.pause()
        playerManager.clearAll()
        EnhancedMusicPlayerManager.stop()
        EnhancedMusicPlayerManager.clearCurrentTrack()
    }

    fun clearVideo() {
        loads.next()
        loads.cancel()
        recovery.onPlaybackRequested()
        watchSessions.finalizeActiveSession()
        playerManager.stop()
        playerManager.stopBackgroundService()
        playerManager.clearAll()
        GlobalPlayerState.setCurrentVideo(null)
        GlobalPlayerState.setExplicitBackgroundPlaybackActive(false)

        uiState.update { it.clearedForNoVideo() }

        navigationHistory.clear()
        _canGoPrevious.value = false

        collaborators.comments.clear()
        collaborators.transcripts.clear()
    }

    fun retryLoadVideo() {
        val video = uiState.value.cachedVideo ?: return
        Log.d(TAG, "Retrying video load for ${video.id}")
        if (upcomingPremiere.applyCountdown(video)) {
            return
        }
        val deviceFileUri = LocalMediaIds.videoUri(video.id)
        if (deviceFileUri != null) {
            playLocalVideo(video, deviceFileUri.toString())
            return
        }
        recovery.onPlaybackRequested()
        playerManager.clearCurrentVideo()
        pluginVideo.forget(video.id)
        uiState.update { it.copy(error = null, errorHint = null, isLoading = true) }
        loadVideoInfo(video.id, forceRefresh = true)
    }

    fun ensurePlaybackPrepared(videoId: String) {
        val state = uiState.value
        if (state.blocksLatePrepare() || !state.holdsVideo(videoId)) return
        if (playerManager.isPreparedForPlayback(videoId)) return

        scope.launch {
            val latest = uiState.value
            if (latest.blocksLatePrepare()) return@launch
            if (playerManager.isPreparedForPlayback(videoId)) return@launch
            sessionApplier.armLatePrepare(LoadContext(videoId, loads.token), latest)
        }
    }

    /** [shuffle] turns the queue's shuffle on or off for this list; null keeps the current setting. */
    fun playPlaylist(
        videos: List<Video>,
        startIndex: Int,
        title: String?,
        shuffle: Boolean?,
    ) {
        if (videos.isEmpty()) return
        val startVideo = videos.getOrNull(startIndex) ?: videos.first()

        EnhancedMusicPlayerManager.stop()
        EnhancedMusicPlayerManager.clearCurrentTrack()

        playerManager.setQueue(videos, startIndex, title, shuffle)

        uiState.update { it.resetForVideo(startVideo).copy(queueTitle = title) }
        watchSessions.saveHistoryEntry(startVideo)
        presence.armNotificationFor(startVideo)
        if (upcomingPremiere.applyCountdown(startVideo, preserveQueueTitle = title)) {
            return
        }
        loadVideoInfo(startVideo.id, forceRefresh = true)
    }

    fun playNext() {
        val handledByPlayer = playerManager.playNext(loadStreamsInPlayer = false)
        if (!handledByPlayer) {
            uiState.value.relatedVideos.firstOrNull()?.let { nextVideo ->
                playVideo(nextVideo)
                GlobalPlayerState.setCurrentVideo(nextVideo)
            }
        }
    }

    fun playPrevious() {
        val handledByPlayer = playerManager.playPrevious(loadStreamsInPlayer = false)
        if (!handledByPlayer) {
            previousVideoId()?.let { prevId ->
                val prevVideo = blankVideo(prevId, cached = null)
                playVideo(prevVideo)
                GlobalPlayerState.setCurrentVideo(prevVideo)
            }
        }
    }

    private fun previousVideoId(): String? =
        navigationHistory.previous()?.also {
            _canGoPrevious.value = navigationHistory.canGoPrevious
        }

    /**
     * Resolves [videoId] through the video plugin and lands it on the screen. A device file plays
     * without the network, a premiere the screen already holds goes straight to its countdown, and a
     * repeat trigger for the load in flight adds nothing.
     *
     * @param forceRefresh loads even when the screen appears to hold the video already.
     * @param resumePositionOverrideMs where an expiry reload picks playback up again.
     */
    fun loadVideoInfo(
        videoId: String,
        forceRefresh: Boolean,
        resumePositionOverrideMs: Long? = null,
    ) {
        notes.observe(videoId)
        if (LocalMediaIds.isLocal(videoId)) {
            // A device file never touches the network; a queue of them arrives here one by one.
            val uri = LocalMediaIds.videoUri(videoId) ?: return
            val video = uiState.value.cachedVideo?.takeIf { it.id == videoId } ?: return
            prepareDeviceFile(video, uri.toString())
            return
        }
        recovery.onLoadStarted(videoId)

        if (upcomingPremiere.applyCachedCountdown(videoId)) return

        uiState.value.loadSkipReason(videoId, forceRefresh)?.let { skip ->
            Log.d(TAG, "Video $videoId skipped: $skip")
            return
        }

        navigationHistory.push(videoId)
        _canGoPrevious.value = navigationHistory.canGoPrevious

        uiState.value = uiState.value.beginLoadFor(videoId)
        collaborators.liveChat.stop()

        if (loads.isLoading(videoId)) return

        val request =
            PlaybackResolutionRequest(
                videoId = videoId,
                resumePositionOverrideMs = resumePositionOverrideMs,
                allowShorts = shortsEnabled(),
            )
        loads.launch(videoId) { load ->
            resolver.resolve(
                request = request,
                cached = uiState.value.cachedVideo?.takeIf { it.id == videoId },
                isCurrent = { loads.isCurrent(load.token) },
                onStep = { step -> sessionApplier.apply(step, load) },
            )
        }
    }

    private companion object {
        const val TAG = "VideoPlayerViewModel"
    }
}
