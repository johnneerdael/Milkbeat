package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.plugin.runtime.PluginCallException
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import java.io.IOException

internal fun rejectAudioOnlyHlsPicture(
    stream: AudioStream,
    picture: Boolean,
) {
    if (picture && stream.serverAbr == null && isHlsStream(stream) && stream.requireAudioOnlyHls) throw PictureUnavailable()
}

internal fun validateAudioStream(
    pluginId: String,
    stream: AudioStream,
    allowedHosts: List<String>,
) {
    stream.audioFormat?.let { format ->
        if (format.type != FormatType.AUDIO || format.id != stream.renditionId || format.url != stream.url ||
            format.mimeType
                .substringBefore(';')
                .trim()
                .lowercase() !=
            stream.mimeType
                .substringBefore(';')
                .trim()
                .lowercase()
        ) {
            throw IOException("The audio presentation does not identify the chosen rendition")
        }
        checkedPluginMediaUrl(format.url, allowedHosts)
        stream.video
            ?.url
            ?.takeIf { it.isNotBlank() }
            ?.let { checkedPluginMediaUrl(it, allowedHosts) }
    }
    val drm = stream.drm ?: return
    try {
        checkedPluginDrmUrl(drm.licenseUrl, allowedHosts)
    } catch (error: IOException) {
        throw PluginCallException(pluginId, PluginError(PluginErrorCode.UNSUPPORTED, error.message ?: "Invalid plugin DRM destination"))
    }
}

internal fun isHlsStream(stream: AudioStream): Boolean =
    stream.mimeType
        .substringBefore(';')
        .trim()
        .lowercase() in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")
