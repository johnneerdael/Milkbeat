package io.github.aedev.flow.plugin.playback

import androidx.media3.common.C
import nl.neerdael.milkbeat.plugin.AudioStream

/** Plugins written before API 10 relied on the host's 512 KiB ranges, whatever their server needed. */
internal const val LEGACY_RANGE_REQUEST_BYTES = 512 * 1024L

// A music video's picture runs at megabits a second; audio-sized ranges would need a request
// every second and leave the picture waiting on round trips.
private const val PICTURE_RANGE_REQUEST_BYTES = 4 * 1024 * 1024L

/** How the host cuts one stream's progressive requests; no policy fetches the file in one request. */
sealed interface PluginRangePolicy {
    /** The ranges every plugin got before API 10, kept exactly for plugins that rely on them. */
    data object Legacy : PluginRangePolicy

    /** The provider's own maximum: [PluginRangedDataSource] keeps every network request within it. */
    data class Declared(
        val maxBytes: Long,
    ) : PluginRangePolicy
}

/** The range policy for this stream from a plugin targeting [pluginApiTarget]. */
internal fun AudioStream.rangePolicyFor(pluginApiTarget: Int?): PluginRangePolicy? =
    rangeRequestBytes?.let(PluginRangePolicy::Declared)
        ?: PluginRangePolicy.Legacy.takeIf { (pluginApiTarget ?: 0) < 10 }

/**
 * The length of one progressive open. Media3 takes a bounded open as the end of the file, so a
 * declared size never bounds the open: the network transport splits it instead. Legacy ranges keep
 * their former shape: the player's own bounded length passes through, and a picture's first range
 * stays audio-sized (it only has to reveal the stream's layout) before widening.
 */
internal fun pluginRequestLength(
    requestedLength: Long,
    position: Long,
    picture: Boolean,
    policy: PluginRangePolicy?,
): Long =
    when (policy) {
        null, is PluginRangePolicy.Declared -> {
            if (requestedLength > 0) requestedLength else C.LENGTH_UNSET.toLong()
        }

        PluginRangePolicy.Legacy -> {
            when {
                requestedLength > 0 -> requestedLength
                picture && position > 0 -> PICTURE_RANGE_REQUEST_BYTES
                else -> LEGACY_RANGE_REQUEST_BYTES
            }
        }
    }
