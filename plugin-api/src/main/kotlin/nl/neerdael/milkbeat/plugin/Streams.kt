package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.Serializable
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import nl.neerdael.milkbeat.catalog.TrackDescriptor

/**
 * A track to resolve for listening. [video] requires a picture; [prepareVideo] asks for optional
 * picture metadata so the host can enable its track without rebuilding the audio source.
 * Neither flag instructs the plugin to fetch picture media. [maxVideoHeight] and [videoCodecs] describe what this TV decodes:
 * codec keys `h264`, `vp9`, `hevc`, `av1`, those it decodes in hardware, best first.
 */
@Serializable
data class ResolveAudioRequest(
    val track: TrackDescriptor,
    val quality: AudioQuality = AudioQuality.AUTO,
    val video: Boolean = false,
    val maxVideoHeight: Int? = null,
    val videoCodecs: List<String> = emptyList(),
    val language: String? = null,
    /** The stream the host got last time failed; the plugin should not hand back the same one. */
    val failure: StreamFailure? = null,
    /** Prepare an optional picture track alongside audio. Audio-only responses remain valid. */
    val prepareVideo: Boolean = false,
)

/** A track another plugin describes, for an audio plugin to find in its own catalog. */
@Serializable
data class MatchAudioRequest(
    val track: TrackDescriptor,
    val strategy: AudioMatchStrategy = AudioMatchStrategy.SONGS,
)

@Serializable
enum class AudioMatchStrategy { SONGS, ALTERNATE_SONGS, VIDEOS }

@Serializable
data class MatchAudioBatchRequest(
    val tracks: List<TrackDescriptor>,
    val strategy: AudioMatchStrategy = AudioMatchStrategy.SONGS,
    val playlist: PrivatePlaylistImportRequest? = null,
)

/** Result slots correspond to request tracks, including misses and transient failures. */
@Serializable
data class AudioMatchesBatch(
    val matches: List<AudioMatches>,
    val playlist: PrivatePlaylistImportResult? = null,
    val playlistError: PluginError? = null,
)

/**
 * The audio plugin's own tracks that may be [MatchAudioRequest.track], best first, each described in
 * the plugin's id space. The host scores them (title, artists, duration; an ISRC or exact id is
 * certain), so every audio plugin is judged the same way.
 */
@Serializable
data class AudioMatches(
    val candidates: List<TrackDescriptor> = emptyList(),
    val error: PluginError? = null,
)

/** What went wrong with a stream: the URL the host fetched and the HTTP status it got, if any. */
@Serializable
data class StreamFailure(
    val url: String,
    val status: Int? = null,
    val reloadPlaybackContext: String? = null,
    /** Protocol failures are independent of the transport HTTP status. */
    val serverAbrFailure: ServerAbrFailure? = null,
)

@Serializable
enum class AudioQuality {
    AUTO,
    HIGH,
    MEDIUM,
    LOW,
}

/**
 * Where the host fetches a track's sound, and optionally its picture. Progressive URLs must accept
 * HTTP range requests; HLS URLs identify a playlist. The host asks again once [expiresInMs] has passed. [cacheKey]
 * names the bytes across URL refreshes, so cached audio stays valid; [renditionId] names the exact
 * encoding, and a new one makes the host drop what it cached under [cacheKey].
 */
@Serializable
data class AudioStream(
    val url: String,
    val cacheKey: String,
    val renditionId: String,
    val mimeType: String,
    val codecs: String? = null,
    val bitrate: Int? = null,
    val contentLength: Long? = null,
    val headers: Map<String, String> = emptyMap(),
    /** How long the URL stays valid from now; relative, because TV clocks are often wrong. */
    val expiresInMs: Long? = null,
    /** Integrated loudness relative to the provider's reference, for volume normalisation. */
    val loudnessDb: Double? = null,
    /** Opaque; handed back with [ReportPlaybackRequest] so the plugin can report the listen. */
    val trackingToken: String? = null,
    val video: MediaFormat? = null,
    /** Platform DRM for this rendition; license credentials are distinct from media headers. */
    val drm: AudioDrm? = null,
    val serverAbr: ServerAbrPlayback? = null,
    val artwork: Artwork? = null,
    /** Require the native HLS parser to select an independently advertised audio rendition. */
    val requireAudioOnlyHls: Boolean = false,
    /** Exact chosen audio rendition, including byte ranges for one native DASH presentation. */
    val audioFormat: MediaFormat? = null,
)

/** License information consumed by the host's platform DRM implementation. */
@Serializable
data class AudioDrm(
    val scheme: AudioDrmScheme,
    val licenseUrl: String,
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
enum class AudioDrmScheme { WIDEVINE, }

/** A played-enough listen or view, reported to the provider's history when the listener allows it. */
@Serializable
data class ReportPlaybackRequest(
    val entity: EntityRef,
    val trackingToken: String? = null,
    val playedMs: Long,
    val durationMs: Long? = null,
)

/** A video to play in the video player. */
@Serializable
data class ResolveVideoRequest(
    val entity: EntityRef,
    val maxHeight: Int? = null,
    val codecs: List<String> = emptyList(),
    /** Preferred audio track (dub) language. */
    val language: String? = null,
    /** Captions wanted in this language, translated by the provider when it can. */
    val captionLanguage: String? = null,
    val failure: StreamFailure? = null,
)

@Serializable
enum class VideoKind {
    VOD,
    LIVE,

    /** Scheduled; [VideoPlayback.startsInMs] says when. */
    UPCOMING,
}

/**
 * Everything the video player needs: the formats to merge into one picture and one sound, or the
 * live manifests, plus captions, chapters and segments listeners may skip.
 */
@Serializable
data class VideoPlayback(
    val kind: VideoKind,
    val details: VideoDetails,
    val formats: List<MediaFormat> = emptyList(),
    val hlsUrl: String? = null,
    val dashUrl: String? = null,
    val captions: List<CaptionTrack> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    val skipSegments: List<SkipSegment> = emptyList(),
    val headers: Map<String, String> = emptyMap(),
    val expiresInMs: Long? = null,
    val startsInMs: Long? = null,
    /**
     * The URLs open only this long after the answer, e.g. once a provider's pre-roll ad could have
     * been skipped; the host waits out what is left when playback starts, so a resolve made ahead
     * (a queued or autoplayed video) waits for nothing.
     */
    val availableInMs: Long? = null,
    /** A live stream the listener can seek back in. */
    val dvr: Boolean = false,
    val trackingToken: String? = null,
    val serverAbr: ServerAbrPlayback? = null,
)

@Serializable
data class VideoDetails(
    val entity: EntityRef,
    val title: String,
    val channelName: String? = null,
    val channel: EntityRef? = null,
    val channelAvatar: Artwork? = null,
    val durationSeconds: Int? = null,
    val description: String? = null,
    val viewsLabel: String? = null,
    val publishedLabel: String? = null,
    val artwork: Artwork? = null,
    val keywords: List<String> = emptyList(),
)

@Serializable
enum class FormatType {
    AUDIO,
    VIDEO,
}

/** One downloadable rendition. The host plays a video format and an audio format side by side. */
@Serializable
data class MediaFormat(
    val id: String,
    val type: FormatType,
    val url: String,
    val mimeType: String,
    val codecs: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val fps: Int? = null,
    val bitrate: Int? = null,
    val averageBitrate: Int? = null,
    val contentLength: Long? = null,
    val durationMs: Long? = null,
    val initRange: ByteRange? = null,
    val indexRange: ByteRange? = null,
    val qualityLabel: String? = null,
    val hdr: Boolean = false,
    val audioTrack: AudioTrackInfo? = null,
    /** Headers this format needs beyond the playback's own, e.g. the user agent of the client that found it. */
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class ByteRange(
    val start: Long,
    val end: Long,
)

@Serializable
data class AudioTrackInfo(
    val id: String,
    val name: String? = null,
    val language: String? = null,
    val original: Boolean = false,
    /** Dynamic range compressed, for quiet listening. */
    val drc: Boolean = false,
)

@Serializable
data class CaptionTrack(
    val url: String,
    val language: String,
    val name: String,
    val autoGenerated: Boolean = false,
    /** Machine-translated from another language. */
    val translated: Boolean = false,
    val mimeType: String = "text/vtt",
)

@Serializable
data class Chapter(
    val title: String,
    val startMs: Long,
)

/** A stretch listeners may skip, such as a sponsor read; [category] is shown on the skip button. */
@Serializable
data class SkipSegment(
    val startMs: Long,
    val endMs: Long,
    val category: String,
)
