package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import java.io.IOException

/** The recording still has audio; only the accepted picture request is unavailable. */
internal class SabrPictureUnavailable : IOException("SABR presentation has no picture")

internal fun Throwable.isSabrPictureUnavailable(): Boolean = generateSequence(this) { it.cause }.any { it is SabrPictureUnavailable }

/** Validate the native protocol marker before any conventional URL/cache resolution occurs. */
internal fun validateServerAbr(
    playback: ServerAbrPlayback?,
    picture: Boolean,
    allowedHosts: List<String>,
) {
    if (playback == null) return
    checkedPluginMediaUrl(playback.url, allowedHosts)
    if (playback.videoId.isBlank() || playback.config.isBlank() || playback.client.clientVersion.isBlank() || playback.formats.isEmpty()) {
        throw IOException("Incomplete SABR presentation")
    }
    if (!playback.live && (playback.durationMs ?: 0L) <= 0L) {
        throw IOException("VOD SABR presentation requires a positive duration")
    }
    if (playback.formats.none { it.format.type == FormatType.AUDIO }) throw IOException("SABR presentation has no audio")
    if (picture && playback.formats.none { it.format.type == FormatType.VIDEO }) throw SabrPictureUnavailable()
    if (!picture &&
        playback.formats.any { it.format.type == FormatType.VIDEO }
    ) {
        throw IOException("Audio-only SABR included picture formats")
    }
    val identities = HashSet<Triple<Int, String, String?>>()
    for (tuple in playback.formats) {
        if (tuple.itag <= 0 || runCatching { java.lang.Long.parseUnsignedLong(tuple.lastModified) }.isFailure ||
            tuple.format.mimeType.isBlank() || tuple.format.codecs.isNullOrBlank() ||
            !identities.add(Triple(tuple.itag, tuple.lastModified, tuple.xTags))
        ) {
            throw IOException("Invalid SABR format identity")
        }
        // Empty direct URLs are expected: the native protocol owns every representation.
        if (tuple.format.url.isNotBlank()) checkedPluginMediaUrl(tuple.format.url, allowedHosts)
    }
}

internal fun requireNativeSabrMarker(
    mimeType: String,
    playback: ServerAbrPlayback?,
) {
    if (mimeType.substringBefore(';').trim().equals("application/x-server-abr", ignoreCase = true) && playback == null) {
        throw IOException("SABR playback requires a native presentation")
    }
}
