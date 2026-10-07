package io.github.aedev.flow.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.coroutines.flow.MutableStateFlow

@Volatile
private var videoCapablePlaybackIds: Set<String> = emptySet()

internal val musicVideoAvailableState = MutableStateFlow(false)

internal fun EnhancedMusicPlayerManager.canShowVideo(track: MusicTrack): Boolean =
    (track.isVideoSong || track.videoId in videoCapablePlaybackIds) && track.videoId !in videoUnavailableIds

internal fun EnhancedMusicPlayerManager.carriesPicture(track: MusicTrack): Boolean = showVideo && canShowVideo(track)

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.buildMediaItem(
    track: MusicTrack,
    uri: Uri = streamUri(track),
    useCacheKey: Boolean = !LocalMediaIds.isLocal(track.videoId),
): MediaItem {
    val builder =
        MediaItem
            .Builder()
            .setUri(uri)
            .setMediaId(track.videoId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(Uri.parse(track.highResThumbnailUrl))
                    .setExtras(
                        if (uri.scheme in setOf(MusicVideoItems.SCHEME, MusicVideoItems.SONG_SCHEME)) {
                            Bundle().apply { putString(QUEUE_PREPARATION_URI, uri.toString()) }
                        } else {
                            null
                        },
                    ).build(),
            )

    if (useCacheKey) {
        builder.setCustomCacheKey(track.videoId)
    }
    if (uri.scheme == MusicVideoItems.SCHEME || uri.scheme == MusicVideoItems.SONG_SCHEME) {
        streamItemIds += track.videoId
        if (uri.scheme == MusicVideoItems.SCHEME) videoItemIds += track.videoId else videoItemIds -= track.videoId
    } else {
        streamItemIds -= track.videoId
        videoItemIds -= track.videoId
    }

    return builder
        .build()
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performSetVideoMode(show: Boolean) {
    if (show != showVideo) Log.d("EnhancedMusicPlayer", "Music video pictures ${if (show) "shown" else "hidden"}")
    showVideo = show
    val controller = player ?: return
    val playing = controller.currentMediaItemIndex
    val position = controller.currentPosition
    val tracks = queueState.value.associateBy { it.videoId }
    for (index in (if (show) playing.coerceAtLeast(0) else playing + 1) until controller.mediaItemCount) {
        val track = tracks[controller.getMediaItemAt(index).mediaId]?.takeIf(::canShowVideo) ?: continue
        if (track.videoId !in streamItemIds || (track.videoId in videoItemIds) == carriesPicture(track)) continue
        controller.replaceMediaItem(index, buildMediaItem(track))
        if (index == playing) controller.seekTo(index, position)
    }
    applyVideoMode(controller)
}

/** Updates choices from accepted audio resolutions without loading or replacing any source. */
@OptIn(UnstableApi::class)
fun EnhancedMusicPlayerManager.setVideoCapablePlaybackIds(ids: Set<String>) {
    videoCapablePlaybackIds = ids.toSet()
    musicVideoAvailableState.value = currentTrackState.value?.let(::canShowVideo) == true
    player?.let(::applyVideoMode)
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performAcquireVideoSurface() {
    videoSurfaces++
    player?.let(::applyVideoMode)
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performReleaseVideoSurface() {
    videoSurfaces = (videoSurfaces - 1).coerceAtLeast(0)
    player?.let(::applyVideoMode)
}

/** The service found no playable picture; the recording remains audio-only for this session. */
@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.performOnVideoUnavailable(videoId: String) {
    videoUnavailableIds += videoId
    videoItemIds -= videoId
    player?.let(::applyVideoMode)
}

@OptIn(UnstableApi::class)
internal fun EnhancedMusicPlayerManager.applyVideoMode(controller: Player) {
    val track = currentTrackState.value
    musicVideoAvailableState.value = track?.let(::canShowVideo) == true
    videoShownState.value = showVideo && musicVideoAvailableState.value && track != null && track.videoId in videoItemIds
    val play = videoShownState.value && videoSurfaces > 0
    val disabled = androidx.media3.common.C.TRACK_TYPE_VIDEO in controller.trackSelectionParameters.disabledTrackTypes
    if (disabled != play) return
    controller.trackSelectionParameters =
        controller.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_VIDEO, !play)
            .build()
}
