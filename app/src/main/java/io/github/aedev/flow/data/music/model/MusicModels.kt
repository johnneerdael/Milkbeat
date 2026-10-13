package io.github.aedev.flow.data.music.model

import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.utils.ThumbnailUrlResolver
import kotlinx.serialization.Serializable

@Serializable
enum class MusicQueueOrigin { USER, RADIO }

enum class MusicItemType { SONG, ALBUM, PLAYLIST, ARTIST }

/**
 * Play-source prefix marking a genre/mood-scoped surface (genre rows, mood
 * chips). The player strips it for the "Playing from" label and hands the genre
 * to the music brain as listen-context provenance.
 */
const val MUSIC_GENRE_SOURCE_PREFIX = "genre:"

/** A song listed as a video (a YouTube Music playlist, a queued download) as the music player's track. */
fun Video.toMusicTrack(): MusicTrack =
    MusicTrack(
        videoId = id,
        title = title,
        artist = channelName,
        thumbnailUrl = thumbnailUrl,
        duration = duration,
        views = viewCount.coerceAtLeast(0),
        channelId = channelId,
    )

@Serializable
data class MusicTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val duration: Int,
    val views: Long = 0,
    val likes: Long = 0,
    val sourceUrl: String = "", // Full URL for NewPipe extraction
    val album: String = "",
    val channelId: String = "",
    val isExplicit: Boolean? = false,
    val isVideoSong: Boolean = false,
    val albumId: String? = null,
    val artists: List<MusicArtist> = emptyList(),
    val itemType: MusicItemType = MusicItemType.SONG,
    /** The plugin that described this track; its audio plugins resolve it from [descriptor]. */
    val provider: String? = null,
    /** The plugin's `TrackDescriptor`, as PluginJson text, so the queue survives a restart intact. */
    val descriptor: String? = null,
    val queueOrigin: MusicQueueOrigin = MusicQueueOrigin.USER,
    val sourcePosition: Int? = null,
) {
    val highResThumbnailUrl: String
        get() = ThumbnailUrlResolver.resolveMusicThumbnail(videoId, thumbnailUrl, 1080)

    val listThumbnailUrl: String
        get() = ThumbnailUrlResolver.resolveMusicThumbnail(videoId, thumbnailUrl, 256)
}

@Serializable
data class MusicArtist(
    val name: String,
    val id: String? = null,
)

/**
 * Repairs a Gson-deserialized track whose [MusicTrack.artists] may hold untyped maps
 * instead of [MusicArtist] objects: release builds that lose the field's generic
 * signature make Gson fall back to LinkedTreeMap entries, which crash with a
 * ClassCastException on first element access (issue #996). filterIsInstance performs
 * only instanceof checks, so it is safe on a poisoned list; bad entries are dropped
 * and the plain [MusicTrack.artist]/[MusicTrack.channelId] fallbacks take over.
 * Call it on every Gson read path that yields a [MusicTrack].
 */
fun MusicTrack.withTypedArtists(): MusicTrack {
    val raw: List<*>? = artists
    val origin: MusicQueueOrigin? = queueOrigin
    if (raw != null && raw.all { it is MusicArtist } && origin != null) return this
    return copy(artists = raw.orEmpty().filterIsInstance<MusicArtist>(), queueOrigin = origin ?: MusicQueueOrigin.USER)
}

data class MusicPlaylist(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val trackCount: Int = 0,
    val author: String = "",
    // Structured attribution for "not interested"/"don't recommend" filtering.
    // `author` is a display subtitle — album cards put the release YEAR there —
    // so feedback matching must never rely on parsing it.
    val authorId: String? = null,
    val authorName: String? = null,
)

data class PlaylistDetails(
    val id: String,
    val title: String,
    val thumbnailUrl: String,
    val author: String,
    val authorId: String? = null,
    val authorAvatarUrl: String? = null,
    val trackCount: Int,
    val description: String? = null,
    val views: Long? = null,
    val durationText: String? = null,
    val dateText: String? = null,
    val tracks: List<MusicTrack> = emptyList(),
    val continuation: String? = null,
    val otherVersions: List<MusicPlaylist> = emptyList(),
    /** The YouTube playlist whose own mix follows this collection once it has played through. */
    val radioPlaylistId: String? = null,
)

data class ArtistDetails(
    val name: String,
    val channelId: String,
    val thumbnailUrl: String,
    val subscriberCount: Long,
    val monthlyListenersText: String? = null,
    val description: String = "",
    val bannerUrl: String = "",
    val topTracks: List<MusicTrack> = emptyList(),
    val albums: List<MusicPlaylist> = emptyList(),
    val singles: List<MusicPlaylist> = emptyList(),
    val videos: List<MusicTrack> = emptyList(),
    val relatedArtists: List<ArtistDetails> = emptyList(),
    val featuredOn: List<MusicPlaylist> = emptyList(),
    val isSubscribed: Boolean = false,
    val albumsBrowseId: String? = null,
    val albumsParams: String? = null,
    val singlesBrowseId: String? = null,
    val singlesParams: String? = null,
    val topTracksBrowseId: String? = null,
    val topTracksParams: String? = null,
)
