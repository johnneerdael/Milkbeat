package io.github.aedev.flow.plugin.playback

import android.util.Log
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.StreamRequestHeaders
import nl.neerdael.milkbeat.plugin.CaptionTrack
import nl.neerdael.milkbeat.plugin.Chapter
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import nl.neerdael.milkbeat.plugin.SkipSegment
import nl.neerdael.milkbeat.plugin.VideoDetails
import nl.neerdael.milkbeat.plugin.VideoKind
import nl.neerdael.milkbeat.plugin.VideoPlayback
import org.schabi.newpipe.extractor.services.youtube.ItagItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamSegment
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale
import org.schabi.newpipe.extractor.MediaFormat as ContainerFormat

private const val TAG = "PluginVideoStreams"
private const val DEFAULT_FPS = 30

// The contract carries no channel count; the adaptive audio a video plugin offers is stereo.
private const val STEREO = 2
private const val DRC_SUFFIX = ":drc"
private const val UNTAGGED_TRACK = "default"

/**
 * A video a plugin resolved, in the shapes the player already plays: adaptive streams it builds its
 * DASH manifests from, captions as sidecar text tracks, chapters, the segments the skip button
 * offers and the headers the stream's host wants.
 */
data class PlayableVideo(
    val video: Video,
    val durationSeconds: Long,
    val isLive: Boolean,
    val hlsUrl: String?,
    val dashUrl: String?,
    val videoStreams: List<VideoStream>,
    val audioStreams: List<AudioStream>,
    val subtitles: List<SubtitlesStream>,
    val chapters: List<StreamSegment>,
    val skipSegments: List<SponsorBlockSegment>,
    val requestHeaders: StreamRequestHeaders,
    val serverAbr: ServerAbrPlayback? = null,
    val boundServerAbr: BoundServerAbr? = null,
)

/** Maps a plugin's [VideoPlayback] onto what the player plays. Pure. */
internal object PluginVideoStreams {
    fun playable(
        playback: VideoPlayback,
        cached: Video?,
        receivedAtElapsedMs: Long = 0L,
        boundServerAbr: BoundServerAbr? = null,
    ): PlayableVideo {
        val formatDuration =
            playback.formats
                .mapNotNull { it.durationMs }
                .maxOrNull()
                ?.div(1000L)
        val durationSeconds =
            playback.details.durationSeconds
                ?.toLong()
                ?.takeIf { it > 0 }
                ?: formatDuration?.takeIf { it > 0 }
                ?: playback.serverAbr
                    ?.durationMs
                    ?.div(1000L)
                    ?.takeIf { it > 0 }
                ?: cached
                    ?.duration
                    ?.toLong()
                    ?.takeIf { it > 0 }
                ?: 0L
        return PlayableVideo(
            video = video(playback.details, playback.kind, durationSeconds, cached),
            durationSeconds = durationSeconds,
            isLive = playback.kind == VideoKind.LIVE,
            hlsUrl = playback.hlsUrl?.takeIf { it.isNotBlank() },
            dashUrl = playback.dashUrl?.takeIf { it.isNotBlank() },
            videoStreams = videoStreams(playback.formats),
            audioStreams = audioStreams(playback.formats),
            subtitles = subtitles(playback.captions),
            chapters = chapters(playback.chapters),
            skipSegments = skipSegments(playback.skipSegments),
            requestHeaders = requestHeaders(playback, receivedAtElapsedMs),
            serverAbr = playback.serverAbr,
            boundServerAbr = boundServerAbr,
        )
    }

    /** The details over what the screen already knew of the video, which the details never erase. */
    fun video(
        details: VideoDetails,
        kind: VideoKind,
        durationSeconds: Long,
        cached: Video?,
    ): Video {
        val id = details.entity.providerId
        val base =
            cached?.takeIf { it.id == id }
                ?: Video(
                    id = id,
                    title = "",
                    channelName = "",
                    channelId = "",
                    thumbnailUrl = "",
                    duration = 0,
                    viewCount = 0L,
                    uploadDate = "",
                )
        return base.copy(
            title = details.title.ifBlank { base.title },
            channelName = details.channelName?.takeIf { it.isNotBlank() } ?: base.channelName,
            channelId = details.channel?.providerId ?: base.channelId,
            thumbnailUrl = details.artwork?.url ?: base.thumbnailUrl,
            duration = if (durationSeconds > 0) durationSeconds.toInt() else base.duration,
            description = details.description ?: base.description,
            channelThumbnailUrl = details.channelAvatar?.url ?: base.channelThumbnailUrl,
            uploadDate = details.publishedLabel ?: base.uploadDate,
            tags = details.keywords.ifEmpty { base.tags },
            isLive = kind == VideoKind.LIVE,
            isUpcoming = kind == VideoKind.UPCOMING,
        )
    }

    fun videoStreams(formats: List<MediaFormat>): List<VideoStream> =
        formats
            .filter { it.type == FormatType.VIDEO && it.url.isNotBlank() && (it.height ?: 0) > 0 }
            .mapNotNull { format ->
                val container = containerOf(format) ?: return@mapNotNull null
                val resolution = format.qualityLabel?.takeIf { it.isNotBlank() } ?: "${format.height}p"
                runCatching {
                    VideoStream
                        .Builder()
                        .setId(itagOf(format).toString())
                        .setItagItem(itagItem(format, container, resolution))
                        .setContent(format.url, true)
                        .setMediaFormat(container)
                        .setResolution(resolution)
                        .setIsVideoOnly(true)
                        .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                        .build()
                }.onFailure { Log.w(TAG, "Skipping video format ${format.id}: ${it.message}") }
                    .getOrNull()
            }

    fun audioStreams(formats: List<MediaFormat>): List<AudioStream> =
        formats
            .filter { it.type == FormatType.AUDIO && it.url.isNotBlank() }
            .preferNonDrc()
            .mapNotNull { format ->
                val container = containerOf(format) ?: return@mapNotNull null
                runCatching {
                    AudioStream
                        .Builder()
                        .setId(itagOf(format).toString())
                        .setItagItem(itagItem(format, container, resolution = null))
                        .setContent(format.url, true)
                        .setMediaFormat(container)
                        .setAverageBitrate(bitrateOf(format))
                        .setDeliveryMethod(DeliveryMethod.PROGRESSIVE_HTTP)
                        .applyTrack(format)
                        .build()
                }.onFailure { Log.w(TAG, "Skipping audio format ${format.id}: ${it.message}") }
                    .getOrNull()
            }

    fun subtitles(captions: List<CaptionTrack>): List<SubtitlesStream> =
        captions.mapNotNull { caption ->
            runCatching {
                SubtitlesStream
                    .Builder()
                    .setContent(caption.url, true)
                    .setMediaFormat(captionFormatOf(caption.mimeType))
                    .setLanguageCode(caption.language)
                    .setAutoGenerated(caption.autoGenerated)
                    .build()
            }.onFailure { Log.w(TAG, "Skipping caption ${caption.language}: ${it.message}") }
                .getOrNull()
        }

    fun chapters(chapters: List<Chapter>): List<StreamSegment> =
        chapters
            .sortedBy { it.startMs }
            .map { StreamSegment(it.title, (it.startMs / 1000L).toInt()) }

    fun skipSegments(segments: List<SkipSegment>): List<SponsorBlockSegment> =
        segments
            .filter { it.endMs > it.startMs }
            .map { segment ->
                SponsorBlockSegment(
                    category = segment.category,
                    segment = listOf(segment.startMs / 1000f, segment.endMs / 1000f),
                    uuid = "${segment.category}@${segment.startMs}-${segment.endMs}",
                    actionType = "skip",
                )
            }

    /** The headers the formats ask for, and when their URLs open: [receivedAtElapsedMs] plus the provider's delay. */
    fun requestHeaders(
        playback: VideoPlayback,
        receivedAtElapsedMs: Long = 0L,
    ): StreamRequestHeaders =
        StreamRequestHeaders(
            common = playback.headers,
            byUrl =
                playback.formats
                    .filter { it.headers.isNotEmpty() }
                    .associate { it.url to it.headers },
            opensAtElapsedMs = playback.availableInMs?.takeIf { it > 0 }?.let { receivedAtElapsedMs + it } ?: 0L,
        )

    /**
     * The player's streams are numbered the way its manifests and codec lookups expect: a format id
     * that starts with a number keeps it, any other gets a stable positive one of its own.
     */
    internal fun itagOf(format: MediaFormat): Int =
        format.id
            .takeWhile { it.isDigit() }
            .toIntOrNull()
            ?.takeIf { it > 0 }
            ?: (format.id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)

    private fun itagItem(
        format: MediaFormat,
        container: ContainerFormat,
        resolution: String?,
    ): ItagItem {
        val itag = itagOf(format)
        val item =
            if (format.type == FormatType.VIDEO) {
                ItagItem(itag, ItagItem.ItagType.VIDEO_ONLY, container, resolution.orEmpty(), format.fps ?: DEFAULT_FPS)
            } else {
                ItagItem(itag, ItagItem.ItagType.AUDIO, container, bitrateOf(format))
            }
        format.codecs?.takeIf { it.isNotBlank() }?.let { item.codec = it }
        item.bitrate = bitrateOf(format)
        format.contentLength?.let { item.contentLength = it }
        format.durationMs?.let { item.approxDurationMs = it }
        format.initRange?.let { range ->
            item.initStart = range.start.toInt()
            item.initEnd = range.end.toInt()
        }
        format.indexRange?.let { range ->
            item.indexStart = range.start.toInt()
            item.indexEnd = range.end.toInt()
        }
        if (format.type == FormatType.VIDEO) {
            format.width?.let { item.width = it }
            format.height?.let { item.height = it }
            format.fps?.let { item.fps = it }
            format.qualityLabel?.let { item.quality = it }
        } else {
            item.audioChannels = STEREO
        }
        return item
    }

    private fun bitrateOf(format: MediaFormat): Int = (format.averageBitrate ?: format.bitrate ?: 0).coerceAtLeast(1)

    private fun AudioStream.Builder.applyTrack(format: MediaFormat): AudioStream.Builder {
        val track = format.audioTrack ?: return this
        setAudioTrackType(if (track.original) AudioTrackType.ORIGINAL else AudioTrackType.DUBBED)
        track.id
            .removeSuffix(DRC_SUFFIX)
            .takeIf { it.isNotBlank() && it != UNTAGGED_TRACK }
            ?.let { setAudioTrackId(it) }
        track.name?.takeIf { it.isNotBlank() }?.let { setAudioTrackName(it) }
        track.language
            ?.let { runCatching { Locale.forLanguageTag(it) }.getOrNull() }
            ?.takeIf { it.language.isNotBlank() }
            ?.let { setAudioLocale(it) }
        return this
    }

    /** A dynamic-range-compressed copy is dropped when its normal twin is there, so it never wins a bitrate pick. */
    private fun List<MediaFormat>.preferNonDrc(): List<MediaFormat> {
        val trackOf = { format: MediaFormat -> format.audioTrack?.id?.removeSuffix(DRC_SUFFIX) ?: UNTAGGED_TRACK }
        val normal = filterNot { it.audioTrack?.drc == true }.mapTo(HashSet(), trackOf)
        if (normal.isEmpty()) return this
        return filterNot { it.audioTrack?.drc == true && trackOf(it) in normal }
    }

    private fun containerOf(format: MediaFormat): ContainerFormat? {
        val mime = format.mimeType.lowercase(Locale.ROOT)
        val codecs = format.codecs?.lowercase(Locale.ROOT).orEmpty()
        return when {
            mime.startsWith("video/mp4") -> ContainerFormat.MPEG_4
            mime.startsWith("video/webm") -> ContainerFormat.WEBM
            mime.startsWith("video/3gpp") -> ContainerFormat.v3GPP
            mime.startsWith("audio/mp4") -> ContainerFormat.M4A
            mime.startsWith("audio/webm") && ("opus" in codecs || "opus" in mime) -> ContainerFormat.WEBMA_OPUS
            mime.startsWith("audio/webm") -> ContainerFormat.WEBMA
            else -> null
        }
    }

    private fun captionFormatOf(mimeType: String): ContainerFormat =
        when (mimeType.lowercase(Locale.ROOT)) {
            "application/ttml+xml" -> ContainerFormat.TTML
            "application/x-subrip" -> ContainerFormat.SRT
            else -> ContainerFormat.VTT
        }
}
