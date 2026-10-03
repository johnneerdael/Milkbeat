package io.github.aedev.flow.ui.screens.player

import android.content.Context
import android.util.Log
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.local.ViewHistory
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.video.OfflineSubtitleStore
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.error.VideoErrorMapper
import io.github.aedev.flow.player.stream.PlaybackFailure
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.github.aedev.flow.player.stream.UpcomingDetails
import io.github.aedev.flow.ui.screens.player.state.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.SubtitlesStream

/** The load a step belongs to: the video it resolved for, and the token saying it is still current. */
internal data class LoadContext(
    val videoId: String,
    val token: Long,
)

/**
 * Applies what one load resolved: the screen state each step lands on, the hand-off to the player,
 * and the metadata fetches that follow it — in the order the load produced them.
 *
 * This is the second writer of the player screen's state, alongside [VideoPlayerViewModel]: the
 * ViewModel owns the session entry points and the player-state mirror, this class owns everything a
 * resolved step and its secondary metadata land on. Both write the one [MutableStateFlow] the
 * ViewModel constructs and passes in, and both gate on the same load token, so a superseded load
 * stops writing at exactly the points it used to.
 *
 * Every collaborator here — the player manager included — is the instance the ViewModel already
 * holds; this class creates, looks up and releases none of them.
 */
internal class PlaybackSessionApplier(
    private val context: Context,
    private val uiState: MutableStateFlow<VideoPlayerUiState>,
    private val isLoadCurrent: (Long) -> Boolean,
    private val playbackPreparer: PlaybackPreparer,
    private val pluginPlayback: PluginPlaybackApplier,
    private val secondaryMetadata: PlayerSecondaryMetadataLoader,
    private val viewHistory: ViewHistory,
    private val playerPreferences: PlayerPreferences,
    private val sponsorBlockRepository: SponsorBlockRepository,
    private val videoDownloadManager: VideoDownloadManager,
    private val offlineSubtitleStore: OfflineSubtitleStore,
    private val playerManager: EnhancedPlayerManager,
    private val scope: CoroutineScope,
    private val networkDispatcher: CoroutineDispatcher,
    private val enterUpcoming: (
        videoId: String,
        releaseMs: Long?,
        relatedVideos: List<Video>,
        loadToken: Long,
        details: UpcomingDetails?,
    ) -> Boolean,
) {
    suspend fun apply(
        step: ResolvedPlayback,
        load: LoadContext,
    ) {
        when (step) {
            is ResolvedPlayback.LocalCopyReady -> {
                uiState.update { it.applyLocalCopyReady(load.videoId, step) }
                if (step.needsSponsorBlockBackfill) backfillSponsorBlockSegments(load.videoId)
                prepareLocalMedia(load, step.localFilePath, step.offlineSegments)
            }

            is ResolvedPlayback.FromPlugin -> {
                pluginPlayback.apply(load, step)
            }

            is ResolvedPlayback.PluginFailed -> {
                pluginPlayback.fail(load, step)
            }

            is ResolvedPlayback.Upcoming -> {
                enterUpcoming(load.videoId, step.releaseTimeMs, step.relatedVideos, load.token, step.details)
                armCountdownMetadata(load, step.relatedVideos)
            }

            is ResolvedPlayback.Failed -> {
                applyPlaybackFailure(load, step)
            }
        }
    }

    fun applySecondary(result: SecondaryMetadata) {
        when (result) {
            is SecondaryMetadata.Related -> publishRelatedVideos(result.videoId, result.videos, result.loadToken)
        }
    }

    suspend fun prepareLocalMedia(
        load: LoadContext,
        localFilePath: String,
        offlineSegments: List<SponsorBlockSegment>?,
        savedPosition: Long? = null,
    ) {
        playbackPreparer.prepareLocalMedia(
            videoId = load.videoId,
            localFilePath = localFilePath,
            offlineSegments = offlineSegments,
            savedPosition = savedPosition ?: viewHistory.getPlaybackPosition(load.videoId).first(),
            durationMs =
                uiState.value.cachedVideo
                    ?.takeIf { it.id == load.videoId }
                    ?.duration
                    ?.times(1000L) ?: 0L,
            subtitles = offlineSubtitlesFor(load.videoId),
            isCurrent = { isLoadCurrent(load.token) },
        )
    }

    /** Re-pushes what the screen already holds when the player turns out to own no media item. */
    suspend fun armLatePrepare(
        load: LoadContext,
        latest: VideoPlayerUiState,
    ) {
        val videoId = load.videoId
        when (val prepare = latest.latePrepare(videoId)) {
            null -> {
                Unit
            }

            is LatePrepare.LocalFile -> {
                Log.w(TAG, "Late prepare: arming local playback for $videoId")
                prepareLocalMedia(
                    load = load,
                    localFilePath = prepare.localFilePath,
                    offlineSegments = prepare.offlineSegments,
                    savedPosition = prepare.savedPosition ?: viewHistory.getPlaybackPosition(videoId).first(),
                )
            }
        }
    }

    private suspend fun applyPlaybackFailure(
        load: LoadContext,
        step: ResolvedPlayback.Failed,
    ) {
        if (!isLoadCurrent(load.token)) return
        val videoError =
            when (step.failure) {
                PlaybackFailure.TIMEOUT -> VideoErrorMapper.fromTimeout(context)
                else -> VideoErrorMapper.from(context, step.cause, load.videoId)
            }
        if (step.failure == PlaybackFailure.UNEXPECTED && !videoError.isRetryable) {
            playerPreferences.markVideoUnplayable(load.videoId)
        }
        uiState.update { it.applyPlaybackFailure(step.relatedVideos, videoError) }
    }

    private fun backfillSponsorBlockSegments(videoId: String) {
        scope.launch(networkDispatcher) {
            try {
                val segments = sponsorBlockRepository.getSegments(videoId)
                // An empty list is stored too, so a video with no segments isn't asked about again.
                videoDownloadManager.saveSponsorBlockData(
                    videoId,
                    sponsorBlockRepository.serializeSegments(segments),
                )
                Log.d(TAG, "Backfilled ${segments.size} SB segments for $videoId")
                if (segments.isNotEmpty()) uiState.update { it.copy(offlineSponsorBlockSegments = segments) }
            } catch (e: Exception) {
                Log.w(TAG, "SB backfill failed for $videoId", e)
            }
        }
    }

    /**
     * A countdown never starts playback, so the related lane cannot wait for the first frame the
     * way a playing video's does.
     */
    fun armCountdownMetadata(
        load: LoadContext,
        relatedVideos: List<Video>,
    ) {
        if (!isLoadCurrent(load.token)) return
        secondaryMetadata.loadRelatedVideos(load.videoId, relatedVideos, load.token, awaitPlayback = false)
    }

    /** The one place related items reach the player: the autoplay queue and the lane together. */
    private fun publishRelatedVideos(
        videoId: String,
        videos: List<Video>,
        loadToken: Long,
    ) {
        if (!isLoadCurrent(loadToken) || videos.isEmpty()) return
        val state = uiState.value
        if (state.cachedVideo?.id != videoId) return

        scope.launch {
            if (!isLoadCurrent(loadToken)) return@launch
            val autoplay = playerPreferences.autoplayEnabled.first()
            if (!isLoadCurrent(loadToken)) return@launch
            playerManager.setAutoplayCandidates(
                sourceVideoId = videoId,
                videos = videos,
                enabled = autoplay,
            )
            uiState.update { it.applyRelatedVideos(videoId, videos) }
        }
    }

    private suspend fun offlineSubtitlesFor(videoId: String): List<SubtitlesStream> =
        if (LocalMediaIds.isLocal(videoId)) emptyList() else offlineSubtitleStore.load(videoId)

    private companion object {
        const val TAG = "PlaybackSessionApplier"
    }
}
