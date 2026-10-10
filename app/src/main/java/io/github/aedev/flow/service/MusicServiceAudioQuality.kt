package io.github.aedev.flow.service

import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.player.audio.AudioQualitySource
import io.github.aedev.flow.player.audio.DeclaredAudio
import io.github.aedev.flow.player.audio.audioQualitySource
import io.github.aedev.flow.player.audio.playbackAudioQuality
import io.github.aedev.flow.player.audio.selectedAudioFormat
import io.github.aedev.flow.player.musicAudioQualityState
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private class AudioQualityOrigin(
    val source: AudioQualitySource?,
    val declared: DeclaredAudio?,
)

/**
 * Publishes what the engine decodes whenever the playing item or its tracks change. Nothing polls:
 * one lookup of the item's source runs per change, off the main thread for the download index.
 */
internal fun Media3MusicService.observeAudioQuality() {
    var lookup: Job? = null
    player.addListener(
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                if (!events.containsAny(Player.EVENT_TRACKS_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION)) return
                lookup?.cancel()
                val item = player.currentMediaItem
                val format = player.currentTracks.selectedAudioFormat()
                if (item == null || format == null) {
                    musicAudioQualityState.value = null
                    return
                }
                lookup =
                    lifecycleScope.launch {
                        val origin = withContext(PerformanceDispatcher.diskIO) { audioQualityOrigin(item) }
                        musicAudioQualityState.value = playbackAudioQuality(item.mediaId, origin.source, format, origin.declared)
                    }
            }
        },
    )
}

private fun Media3MusicService.audioQualityOrigin(item: MediaItem): AudioQualityOrigin {
    val uri = item.localConfiguration?.uri
    val scheme = uri?.scheme
    val descriptor =
        uri
            ?.takeIf { scheme == MusicVideoItems.SONG_SCHEME || scheme == MusicVideoItems.SCHEME }
            ?.let { runCatching { MusicVideoItems.descriptor(it) }.getOrNull() }
            ?: return AudioQualityOrigin(audioQualitySource(scheme, providerName = null), null)
    // The resolver plays a finished download before asking any plugin; name it the same way.
    if (downloadUtil.playsFromDownload(descriptor.ref.providerId)) return AudioQualityOrigin(AudioQualitySource.Download, null)
    val accepted = pluginAudio.acceptedListen(descriptor)
    val name =
        accepted?.let {
            pluginRegistry.state.value
                .plugin(it.pluginId)
                ?.manifest
                ?.name ?: it.pluginId
        }
    val declared =
        accepted?.stream?.let { stream ->
            val chosen = stream.audioFormat
            DeclaredAudio(
                mimeType = chosen?.mimeType ?: stream.mimeType,
                codecs = chosen?.codecs ?: stream.codecs,
                bitrate = chosen?.averageBitrate ?: chosen?.bitrate ?: stream.bitrate,
            )
        }
    return AudioQualityOrigin(audioQualitySource(scheme, name), declared)
}
