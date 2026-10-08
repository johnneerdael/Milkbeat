package io.github.aedev.flow.utils

import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import io.github.aedev.flow.player.stream.VideoCodecUtils

/** The limits a music video's picture plays within on this device. */
object MusicVideoFormats {
    const val MAX_HEIGHT = 2160

    /** The active physical display mode bounds picture requests; an unknown mode retains 1080p. */
    fun heightForDisplay(
        width: Int?,
        height: Int?,
    ): Int = (if (width != null && height != null) minOf(width, height) else 1080).coerceAtMost(MAX_HEIGHT)

    private const val H264 = "h264"

    /** Codec keys this device decodes in hardware; H.264 is always assumed playable. */
    val hardwareCodecs: Set<String> by lazy {
        listOf("h264", "vp9", "hevc", "av1")
            .filter { key ->
                val mime = VideoCodecUtils.mimeTypeForCodecKey(key) ?: return@filter false
                runCatching { MediaCodecUtil.getDecoderInfos(mime, false, false).any { it.hardwareAccelerated } }.getOrDefault(false)
            }.toSet() + H264
    }
}
