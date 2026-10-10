package io.github.aedev.flow.plugin.playback

import androidx.media3.common.C
import nl.neerdael.milkbeat.plugin.AudioStream

/** Plugins written before API 10 relied on the host's 512 KiB ranges, whatever their server needed. */
internal const val LEGACY_RANGE_REQUEST_BYTES = 512 * 1024L

// A music video's picture runs at megabits a second; audio-sized ranges would need a request
// every second and leave the picture waiting on round trips.
private const val PICTURE_RANGE_REQUEST_BYTES = 4 * 1024 * 1024L

/** The range size the host uses for this stream from a plugin targeting [pluginApiTarget]. */
internal fun AudioStream.rangeRequestBytesFor(pluginApiTarget: Int?): Long? =
    rangeRequestBytes ?: LEGACY_RANGE_REQUEST_BYTES.takeIf { (pluginApiTarget ?: 0) < 10 }

/**
 * The length of one progressive request: what the player asked for, else the range its plugin
 * declared, else the rest of the file. Media3 takes a bounded open as the end of the file, so a
 * stream is only cut into ranges when its plugin asks. A picture's first range stays audio-sized:
 * it only has to reveal the stream's layout, and while the picture is hidden the player stops
 * loading right after it.
 */
internal fun pluginRequestLength(
    requestedLength: Long,
    position: Long,
    picture: Boolean,
    rangeRequestBytes: Long?,
): Long {
    if (requestedLength > 0) return requestedLength
    val range = rangeRequestBytes ?: return C.LENGTH_UNSET.toLong()
    return if (picture && position > 0) maxOf(range, PICTURE_RANGE_REQUEST_BYTES) else range
}
