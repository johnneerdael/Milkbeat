package io.github.aedev.flow.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId

private const val KEPT_PERIODS = 4

/** A value per media period, kept for the few periods the player holds at once. */
@OptIn(UnstableApi::class)
internal class RecentPeriods<V : Any> {
    private val values =
        object : LinkedHashMap<MediaPeriodId, V>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<MediaPeriodId, V>?) = size > KEPT_PERIODS
        }

    operator fun get(period: MediaPeriodId?): V? = period?.let(values::get)

    operator fun set(
        period: MediaPeriodId,
        value: V,
    ) {
        values[period] = value
    }
}

/**
 * The audio format the renderer decodes for each period, so the playing one can be named. The
 * renderer reads ahead of playback (gapless preloading starts the next track early), and an adaptive
 * stream switches renditions mid-track, so neither the selected tracks nor the latest input format
 * alone says what is audible now.
 */
@OptIn(UnstableApi::class)
internal class PlayingAudioFormats {
    private val formats = RecentPeriods<Format>()

    /** Records the renderer's new input [format] for [period]; returns it when [period] is [playing]. */
    fun onInput(
        period: MediaPeriodId,
        playing: MediaPeriodId?,
        format: Format,
    ): Format? {
        formats[period] = format
        return format.takeIf { period == playing }
    }

    /** The format already read for the period that just started playing, if any. */
    fun onPlaying(playing: MediaPeriodId?): Format? = formats[playing]
}
