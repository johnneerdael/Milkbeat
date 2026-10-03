package io.github.aedev.flow.ui.screens.player

import android.util.Log
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.PlayerRelatedVideosPolicy
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** What a secondary metadata fetch resolved to, for the player screen to fold into its own state. */
internal sealed interface SecondaryMetadata {
    val videoId: String
    val loadToken: Long

    class Related(
        override val videoId: String,
        override val loadToken: Long,
        val videos: List<Video>,
    ) : SecondaryMetadata
}

/**
 * The metadata the player screen wants *after* the first frame: the related lane.
 *
 * The lane holds at most one job, keyed by the video and the load it belongs to: a repeat call for
 * the same pair while that job is in flight is dropped, and any other pair cancels it. Nothing here
 * writes screen state — every outcome leaves through [onResult] for the caller to apply.
 */
internal class PlayerSecondaryMetadataLoader(
    private val playerManager: EnhancedPlayerManager,
    private val scope: CoroutineScope,
    private val networkDispatcher: CoroutineDispatcher,
    private val currentState: () -> VideoPlayerUiState,
    private val relatedVideosFor: (String) -> List<Video>,
    private val shortsEnabled: () -> Boolean,
    private val isPlaybackCurrent: (Long) -> Boolean,
    private val onResult: (SecondaryMetadata) -> Unit,
    /** The related lane's source: the video plugin. */
    private val fetchRelated: suspend (String) -> List<Video>,
) {
    private class ConcurrentLoad {
        var job: Job? = null
        var videoId: String? = null
        var loadToken: Long = -1L

        fun claim(
            videoId: String,
            loadToken: Long,
        ): Boolean {
            if (this.videoId == videoId && this.loadToken == loadToken) {
                if (job?.isActive == true) return false
            } else {
                cancel()
            }
            this.videoId = videoId
            this.loadToken = loadToken
            return true
        }

        fun holds(
            videoId: String,
            loadToken: Long,
        ): Boolean = this.videoId == videoId && this.loadToken == loadToken

        fun takeOver(
            videoId: String,
            loadToken: Long,
        ) {
            cancel()
            this.videoId = videoId
            this.loadToken = loadToken
        }

        fun cancel() {
            job?.cancel()
            job = null
            videoId = null
            loadToken = -1L
        }
    }

    private val relatedLoad = ConcurrentLoad()

    /** Drops the fetch in flight; the next load re-arms it. */
    fun cancel() {
        relatedLoad.cancel()
    }

    fun loadRelatedVideos(
        videoId: String,
        primaryCandidates: List<Video>,
        loadToken: Long,
        awaitPlayback: Boolean = true,
    ) {
        val selected =
            PlayerRelatedVideosPolicy.select(
                videoId = videoId,
                primary = primaryCandidates,
                fallback = playerManager.relatedCandidatesFor(videoId),
                current = relatedVideosFor(videoId),
                shortsEnabled = shortsEnabled(),
            )
        if (selected.isNotEmpty()) {
            relatedLoad.takeOver(videoId, loadToken)
            publish(videoId, selected, loadToken)
            return
        }

        if (!relatedLoad.claim(videoId, loadToken)) return

        relatedLoad.job =
            scope.launch(networkDispatcher) {
                // Keep this request off the critical startup path. It is only needed when the
                // playback resolver did not provide related items with its initial metadata.
                if (awaitPlayback) awaitPlaybackStarted(videoId)
                if (!isPlaybackCurrent(loadToken) || !relatedLoad.holds(videoId, loadToken)) return@launch

                val managerCandidates = playerManager.relatedCandidatesFor(videoId)
                if (managerCandidates.isNotEmpty()) {
                    publish(videoId, managerCandidates, loadToken)
                    return@launch
                }

                val fallbackCandidates =
                    withTimeoutOrNull(RELATED_FALLBACK_TIMEOUT_MS) {
                        fetchRelated(videoId)
                    }.orEmpty()
                if (!isPlaybackCurrent(loadToken) || !relatedLoad.holds(videoId, loadToken)) return@launch

                val resolved =
                    PlayerRelatedVideosPolicy.select(
                        videoId = videoId,
                        primary = primaryCandidates,
                        fallback = fallbackCandidates,
                        current = currentState().relatedVideos,
                        shortsEnabled = shortsEnabled(),
                    )
                if (resolved.isNotEmpty()) {
                    publish(videoId, resolved, loadToken)
                } else {
                    Log.d(TAG, "No related videos resolved for $videoId")
                }
            }
    }

    private fun publish(
        videoId: String,
        videos: List<Video>,
        loadToken: Long,
    ) = onResult(SecondaryMetadata.Related(videoId = videoId, loadToken = loadToken, videos = videos))

    private suspend fun awaitPlaybackStarted(videoId: String) {
        withTimeoutOrNull(PLAYBACK_STARTED_TIMEOUT_MS) {
            playerManager.playerState.first { state ->
                state.currentVideoId == videoId && (state.isPlaying || state.hasEnded || state.error != null)
            }
        }
    }

    private companion object {
        const val TAG = "PlayerSecondaryMetadata"
        const val PLAYBACK_STARTED_TIMEOUT_MS = 15_000L
        const val RELATED_FALLBACK_TIMEOUT_MS = 10_000L
    }
}
