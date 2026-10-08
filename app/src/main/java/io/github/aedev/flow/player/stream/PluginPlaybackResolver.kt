package io.github.aedev.flow.player.stream

import android.os.SystemClock
import android.util.Log
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.di.IoDispatcher
import io.github.aedev.flow.plugin.playback.PluginVideo
import io.github.aedev.flow.plugin.playback.PluginVideoStreams
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.neerdael.milkbeat.plugin.VideoKind
import nl.neerdael.milkbeat.plugin.VideoPlayback
import java.io.File
import javax.inject.Inject

/**
 * Turns a video id into something the player screen can play, through the listener's video plugin.
 *
 * A downloaded copy still wins and never touches the network. Otherwise one plugin resolve answers
 * everything the screen needs to start: the streams or live manifests, captions, chapters and the
 * segments listeners skip. A premiere comes back as a countdown, a failure as the plugin's own words.
 */
class PluginPlaybackResolver
    @Inject
    constructor(
        private val pluginVideo: PluginVideo,
        private val videoDownloadManager: VideoDownloadManager,
        private val sponsorBlockRepository: SponsorBlockRepository,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        suspend fun resolve(
            request: PlaybackResolutionRequest,
            cached: Video?,
            isCurrent: () -> Boolean,
            onStep: suspend (ResolvedPlayback) -> Unit,
        ) {
            val videoId = request.videoId
            val localCopy = withContext(ioDispatcher) { downloadedCopy(videoId) }
            if (localCopy != null) {
                val storedSegments = videoDownloadManager.getSponsorBlockData(videoId)
                currentCoroutineContext().ensureActive()
                if (!isCurrent()) return
                onStep(
                    ResolvedPlayback.LocalCopyReady(
                        localFilePath = localCopy,
                        offlineSegments = sponsorBlockRepository.parseSegments(storedSegments),
                        needsSponsorBlockBackfill = storedSegments == null,
                    ),
                )
                return
            }

            val step =
                try {
                    withTimeout(LOAD_TIMEOUT_MS) { pluginVideo.resolve(videoId) }.fold(
                        onSuccess = { playback ->
                            stepFor(
                                playback,
                                cached,
                                request.resumePositionOverrideMs,
                                boundServerAbr = pluginVideo.bindServerAbr(playback),
                            )
                        },
                        onFailure = { error -> ResolvedPlayback.PluginFailed(error) },
                    )
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "Video plugin timed out resolving $videoId")
                    ResolvedPlayback.Failed(PlaybackFailure.TIMEOUT, cause = e, relatedVideos = null)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Resolving $videoId failed", e)
                    ResolvedPlayback.PluginFailed(e)
                }
            currentCoroutineContext().ensureActive()
            if (isCurrent()) onStep(step)
        }

        private suspend fun downloadedCopy(videoId: String): String? =
            try {
                videoDownloadManager.downloadedVideos
                    .map { list -> list.find { it.video.id == videoId } }
                    .first()
                    ?.filePath
                    ?.let(::File)
                    ?.takeIf { it.exists() }
                    ?.absolutePath
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

        internal companion object {
            const val TAG = "PluginPlaybackResolver"

            /** The plugin's own ladder is bounded at about 20 s; this only catches a call that never returns. */
            const val LOAD_TIMEOUT_MS = 45_000L

            fun stepFor(
                playback: VideoPlayback,
                cached: Video?,
                resumePositionOverrideMs: Long?,
                nowMs: Long = System.currentTimeMillis(),
                boundServerAbr: io.github.aedev.flow.plugin.playback.BoundServerAbr? = null,
            ): ResolvedPlayback {
                if (playback.kind == VideoKind.UPCOMING) {
                    val details = playback.details
                    return ResolvedPlayback.Upcoming(
                        relatedVideos = emptyList(),
                        releaseTimeMs = playback.startsInMs?.let { nowMs + it },
                        details =
                            UpcomingDetails(
                                title = details.title,
                                channelName = details.channelName.orEmpty(),
                                channelId = details.channel?.providerId.orEmpty(),
                                thumbnailUrl = details.artwork?.url.orEmpty(),
                                description = details.description.orEmpty(),
                            ),
                    )
                }
                val playable = PluginVideoStreams.playable(playback, cached, SystemClock.elapsedRealtime(), boundServerAbr)
                val playableVod =
                    playable.serverAbr != null || playable.hlsUrl != null || playable.dashUrl != null ||
                        playable.videoStreams.isNotEmpty() || playable.audioStreams.isNotEmpty()
                val playableLive = playable.isLive && (playable.hlsUrl != null || playable.dashUrl != null)
                if (!playableVod && !playableLive) {
                    return ResolvedPlayback.Failed(PlaybackFailure.EXTRACTION, cause = null, relatedVideos = null)
                }
                return ResolvedPlayback.FromPlugin(playable, resumePositionOverrideMs)
            }
        }
    }
