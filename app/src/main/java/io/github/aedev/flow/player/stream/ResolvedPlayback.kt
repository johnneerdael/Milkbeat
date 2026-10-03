package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.plugin.playback.PlayableVideo

/** Everything [PluginPlaybackResolver] needs that the player screen owns. */
data class PlaybackResolutionRequest(
    val videoId: String,
    val resumePositionOverrideMs: Long?,
    val allowShorts: Boolean,
)

/** Why a resolution produced nothing to play, and therefore which error string the screen shows. */
enum class PlaybackFailure {
    /** The source answered with nothing the player can play. */
    EXTRACTION,

    /** The whole resolution ran past its budget. */
    TIMEOUT,

    /** An exception nobody in the pipeline expected. */
    UNEXPECTED,
}

/**
 * One thing the player screen can act on, handed over in the order the pipeline produces it.
 *
 * A resolution emits exactly one step. The screen owns every `_uiState` write and every hand-off
 * to the player manager; this type carries only the values those need.
 */
sealed interface ResolvedPlayback {
    /** A downloaded copy of the video exists and should start playing now. */
    data class LocalCopyReady(
        val localFilePath: String,
        val offlineSegments: List<SponsorBlockSegment>?,
        /** The download was saved before SponsorBlock data was, so it is worth fetching once. */
        val needsSponsorBlockBackfill: Boolean = false,
    ) : ResolvedPlayback

    /** A VOD or live stream the video plugin resolved. */
    data class FromPlugin(
        val playable: PlayableVideo,
        val resumePositionOverrideMs: Long?,
    ) : ResolvedPlayback

    /** The video plugin could not resolve the video, or no video plugin is chosen; [cause] says which. */
    data class PluginFailed(
        val cause: Throwable,
    ) : ResolvedPlayback

    /** The video has not premiered yet, so the screen shows a countdown rather than an error. */
    data class Upcoming(
        val relatedVideos: List<Video>,
        val releaseTimeMs: Long?,
        val details: UpcomingDetails? = null,
    ) : ResolvedPlayback

    /** Nothing playable, and not a premiere. A null [relatedVideos] leaves the current list alone. */
    data class Failed(
        val failure: PlaybackFailure,
        val cause: Throwable?,
        val relatedVideos: List<Video>?,
    ) : ResolvedPlayback
}
