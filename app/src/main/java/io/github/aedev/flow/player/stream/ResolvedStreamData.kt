package io.github.aedev.flow.player.stream

import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.StreamRequestHeaders
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream

/**
 * Everything resolving one video's streams produced: enough to start playback, build a preloaded
 * media source, or fill in the player state, without going back to the network.
 */
data class ResolvedStreamData(
    val enrichedVideo: Video,
    val videoStream: VideoStream?,
    val audioStream: AudioStream?,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    val subtitles: List<SubtitlesStream>,
    val durationSeconds: Long,
    val dashManifestUrl: String?,
    val streamType: StreamType?,
    val relatedVideos: List<Video>,
    val preferredCodec: String,
    val hlsUrl: String? = null,
    val requestHeaders: StreamRequestHeaders = StreamRequestHeaders.NONE,
    /** Segments the source already knows; null leaves them to the player's own SponsorBlock lookup. */
    val skipSegments: List<SponsorBlockSegment>? = null,
    val serverAbr: io.github.aedev.flow.plugin.playback.BoundServerAbr? = null,
)
