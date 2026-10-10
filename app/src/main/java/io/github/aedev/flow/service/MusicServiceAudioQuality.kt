package io.github.aedev.flow.service

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.player.audio.AudioQualitySource
import io.github.aedev.flow.player.audio.PlayingAudioFormats
import io.github.aedev.flow.player.audio.audioQualitySource
import io.github.aedev.flow.player.audio.playbackAudioQuality
import io.github.aedev.flow.player.audioOrigin
import io.github.aedev.flow.player.musicAudioQualityState

/**
 * Publishes what the audio renderer decodes for the playing period: when that period starts, and
 * when its input format changes, as an adaptive stream switches rendition. Nothing polls, and the
 * source comes from the playing window's item, where the resolver recorded its choice.
 */
@OptIn(UnstableApi::class)
internal fun Media3MusicService.observeAudioQuality() {
    val formats = PlayingAudioFormats()

    fun publish(
        item: MediaItem?,
        format: Format?,
    ) {
        musicAudioQualityState.value =
            if (item == null || format == null) {
                null
            } else {
                val origin = item.audioOrigin()
                val source =
                    when {
                        origin == null -> {
                            audioQualitySource(item.localConfiguration?.uri?.scheme, LocalMediaIds.isLocal(item.mediaId), null)
                        }

                        origin.pluginId == null -> {
                            AudioQualitySource.Download
                        }

                        else -> {
                            AudioQualitySource.Provider(
                                pluginRegistry.state.value
                                    .plugin(origin.pluginId)
                                    ?.manifest
                                    ?.name ?: origin.pluginId,
                            )
                        }
                    }
                playbackAudioQuality(item.mediaId, source, format, origin?.declared)
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
                formats.onInput(period, eventTime.currentMediaPeriodId, format)?.let { publish(player.currentMediaItem, it) }
            }

            override fun onMediaItemTransition(
                eventTime: AnalyticsListener.EventTime,
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                publish(mediaItem, formats.onPlaying(eventTime.currentMediaPeriodId))
            }
        },
    )
}
