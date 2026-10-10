package io.github.aedev.flow.service

import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.player.audio.AudioQualitySource
import io.github.aedev.flow.player.audio.DeclaredAudio
import io.github.aedev.flow.player.audio.PlayingAudioFormats
import io.github.aedev.flow.player.audio.RecentPeriods
import io.github.aedev.flow.player.audio.audioQualitySource
import io.github.aedev.flow.player.audio.playbackAudioQuality
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
 * Publishes what the audio renderer decodes for the playing period: when that period starts, and
 * when its input format changes, as an adaptive stream switches rendition. Nothing polls. The
 * period's source is looked up once, off the main thread for the download index, and pinned: a
 * sign-in change clears the plugin's accepted streams while the bound source keeps playing.
 */
@OptIn(UnstableApi::class)
internal fun Media3MusicService.observeAudioQuality() {
    val formats = PlayingAudioFormats()
    val origins = RecentPeriods<AudioQualityOrigin>()
    var lookup: Job? = null

    fun publish(
        item: MediaItem?,
        period: MediaPeriodId?,
        format: Format?,
    ) {
        lookup?.cancel()
        if (item == null || period == null || format == null) {
            musicAudioQualityState.value = null
            return
        }
        origins[period]?.let { origin ->
            musicAudioQualityState.value = playbackAudioQuality(item.mediaId, origin.source, format, origin.declared)
            return
        }
        lookup =
            lifecycleScope.launch {
                val origin = withContext(PerformanceDispatcher.diskIO) { audioQualityOrigin(item) }
                origins[period] = origin
                musicAudioQualityState.value = playbackAudioQuality(item.mediaId, origin.source, format, origin.declared)
            }
    }

    player.addAnalyticsListener(
        object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                val period = eventTime.mediaPeriodId ?: return
                formats.onInput(period, eventTime.currentMediaPeriodId, format)?.let { publish(player.currentMediaItem, period, it) }
            }

            override fun onMediaItemTransition(
                eventTime: AnalyticsListener.EventTime,
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                publish(mediaItem, eventTime.currentMediaPeriodId, formats.onPlaying(eventTime.currentMediaPeriodId))
            }
        },
    )
}

private fun Media3MusicService.audioQualityOrigin(item: MediaItem): AudioQualityOrigin {
    val uri = item.localConfiguration?.uri
    val scheme = uri?.scheme
    val localLibraryItem = LocalMediaIds.isLocal(item.mediaId)
    val descriptor =
        uri
            ?.takeIf { scheme == MusicVideoItems.SONG_SCHEME || scheme == MusicVideoItems.SCHEME }
            ?.let { runCatching { MusicVideoItems.descriptor(it) }.getOrNull() }
            ?: return AudioQualityOrigin(audioQualitySource(scheme, localLibraryItem, providerName = null), null)
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
    return AudioQualityOrigin(audioQualitySource(scheme, localLibraryItem, name), declared)
}
