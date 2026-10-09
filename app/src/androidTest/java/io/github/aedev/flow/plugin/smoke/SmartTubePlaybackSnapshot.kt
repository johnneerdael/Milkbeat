package io.github.aedev.flow.plugin.smoke

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.plugin.playback.QueuePreparationResult

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal data class SmartTubePlaybackSnapshot(
    val items: List<MediaItem>,
    val index: Int,
    val position: Long,
    val playing: Boolean,
    val volume: Float,
    val queue: List<MusicTrack>,
    val track: MusicTrack?,
    val prefetcher: (suspend (Uri) -> QueuePreparationResult)?,
    val video: Boolean,
) {
    companion object {
        fun capture(
            controller: MediaController,
            engine: ExoPlayer,
            manager: EnhancedMusicPlayerManager,
        ) = SmartTubePlaybackSnapshot(
            (0 until engine.mediaItemCount).map(engine::getMediaItemAt),
            controller.currentMediaItemIndex,
            controller.currentPosition,
            controller.playWhenReady,
            controller.volume,
            manager.queueState.value.toList(),
            manager.currentTrack.value,
            manager.prefetcher,
            manager.showVideo,
        )
    }

    fun restore(
        controller: MediaController?,
        manager: EnhancedMusicPlayerManager,
    ) {
        if (controller == null) return
        manager.queueState.value = queue
        manager.currentTrackState.value = track
        manager.prefetcher = prefetcher
        manager.setVideoMode(video)
        controller.volume = volume
        if (items.isEmpty()) return
        controller.setMediaItems(items, index.coerceIn(items.indices), position)
        controller.prepare()
        if (playing) controller.play()
    }
}
