package io.github.aedev.flow.ui.tv.catalog

import io.github.aedev.flow.data.model.Playlist
import io.github.aedev.flow.data.model.Video
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.TrackDescriptor

/**
 * A video plugin's item as the player's [Video]. Views and age stay in the item's details, which the
 * card shows as the plugin wrote them.
 */
fun MetadataItem.toTvVideo(): Video {
    val channel = artists.firstOrNull()
    return Video(
        id = entity.providerId,
        title = title,
        channelName = channel?.name ?: subtitle.orEmpty(),
        channelId = channel?.entity?.providerId.orEmpty(),
        thumbnailUrl = artwork?.url ?: track?.artwork?.url.orEmpty(),
        duration = durationSeconds ?: track?.durationMs?.let { (it / MILLIS_PER_SECOND).toInt() } ?: 0,
        viewCount = 0,
        uploadDate = "",
        isLive = live,
        isUpcoming = upcoming,
    )
}

/** A queued track of a video plugin as the player's [Video]. */
fun TrackDescriptor.toTvVideo(): Video {
    val channel = artists.firstOrNull()
    return Video(
        id = ref.providerId,
        title = title,
        channelName = channel?.name.orEmpty(),
        channelId = channel?.entity?.providerId.orEmpty(),
        thumbnailUrl = artwork?.url.orEmpty(),
        duration = durationMs?.let { (it / MILLIS_PER_SECOND).toInt() } ?: 0,
        viewCount = 0,
        uploadDate = "",
    )
}

/** A playlist item as the card's [Playlist]; its count is only known as the plugin's own words. */
fun MetadataItem.toTvPlaylist(): Playlist =
    Playlist(
        id = entity.providerId,
        name = title,
        thumbnailUrl = artwork?.url.orEmpty(),
        videoCount = 0,
        isLocal = false,
    )

private const val MILLIS_PER_SECOND = 1000L
