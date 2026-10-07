package io.github.aedev.flow.data.download

import nl.neerdael.milkbeat.plugin.AudioStream
import java.io.IOException

/** The offline path stores one progressive file and has no playlist or offline-license support. */
internal fun requireDownloadablePluginAudio(stream: AudioStream) {
    val mime =
        stream.mimeType
            .substringBefore(';')
            .trim()
            .lowercase()
    if (stream.drm != null || mime in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")) {
        throw IOException("Offline downloads are unavailable for HLS or protected audio")
    }
}
