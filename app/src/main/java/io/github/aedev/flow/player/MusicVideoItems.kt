package io.github.aedev.flow.player

import android.net.Uri
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.trackDescriptor
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.PluginJson
import java.util.Base64

/**
 * How a track travels through the music player: `music://<id>`, or `musicvideo://<id>` when its
 * picture plays too, carrying the plugin's track descriptor so the data source can resolve it with
 * no other state. The picture half of a music video is cached and resolved under its own key.
 */
object MusicVideoItems {
    const val SCHEME = "musicvideo"
    const val SONG_SCHEME = "music"
    private const val DESCRIPTOR_PARAM = "t"
    private const val VIDEO_KEY_SUFFIX = "#video"

    fun uri(
        track: MusicTrack,
        withPicture: Boolean,
    ): Uri {
        val builder = Uri.Builder().scheme(if (withPicture) SCHEME else SONG_SCHEME).authority(track.videoId)
        track.descriptor?.let { json ->
            builder.appendQueryParameter(DESCRIPTOR_PARAM, Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray()))
        }
        track.playbackContext?.audioProviderId?.let { builder.appendQueryParameter("provider", it) }
        return builder.build()
    }

    /**
     * The track [uri] names. A track saved before tracks carried descriptors (a queue from an older
     * version) is described by its bare id, which YouTube Music resolves.
     */
    fun descriptor(uri: Uri): TrackDescriptor {
        val id = uri.authority.orEmpty()
        val encoded = uri.getQueryParameter(DESCRIPTOR_PARAM)
        val decoded =
            encoded?.let {
                runCatching {
                    PluginJson.decodeFromString(TrackDescriptor.serializer(), String(Base64.getUrlDecoder().decode(it)))
                }.getOrNull()
            }
        return decoded ?: TrackDescriptor(ref = EntityRef(EntityKind.TRACK, id), title = id, ids = mapOf(LEGACY_ID_SPACE to id))
    }

    fun descriptor(track: MusicTrack): TrackDescriptor =
        track.trackDescriptor()
            ?: TrackDescriptor(
                ref = EntityRef(EntityKind.TRACK, track.videoId),
                title = track.title,
                ids =
                    mapOf(LEGACY_ID_SPACE to track.videoId),
            )

    fun preferredProvider(uri: Uri): String? = uri.getQueryParameter("provider")

    /**
     * The song half of the item at [uri], for a fresh resolve: the track's descriptor and provider stay,
     * since without them a provider id would be taken for a YouTube video id.
     */
    fun songUri(
        uri: Uri,
        mediaId: String,
    ): Uri =
        uri
            .buildUpon()
            .scheme(SONG_SCHEME)
            .authority(mediaId)
            .build()

    fun videoKey(videoId: String): String = videoId + VIDEO_KEY_SUFFIX

    /** The video id a cache key names when it is the picture half of a music video, else null. */
    fun videoIdOfVideoKey(key: String): String? = key.takeIf { it.endsWith(VIDEO_KEY_SUFFIX) }?.removeSuffix(VIDEO_KEY_SUFFIX)

    // Before plugins, every track id was a YouTube video id.
    private const val LEGACY_ID_SPACE = "ytm"
}
