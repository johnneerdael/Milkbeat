@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.aedev.flow.plugin.playback

import androidx.media3.common.MimeTypes
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import java.io.IOException

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
    try {
        require(
            java.util.Base64
                .getUrlDecoder()
                .decode(playback.config)
                .isNotEmpty(),
        )
        playback.poToken?.takeIf { it.isNotEmpty() }?.let {
            require(
                java.util.Base64
                    .getUrlDecoder()
                    .decode(it)
                    .isNotEmpty(),
            )
        }
    } catch (invalid: IllegalArgumentException) {
        throw IOException("Invalid encoded SABR configuration or attestation")
    }
    if (!playback.live && (playback.durationMs ?: 0L) <= 0L) {
        throw IOException("VOD SABR presentation requires a positive duration")
    }
    if (playback.formats.none { it.format.type == FormatType.AUDIO }) throw IOException("SABR presentation has no audio")
    if (picture && playback.formats.none { it.format.type == FormatType.VIDEO }) throw PictureUnavailable()
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
        val container =
            MimeTypes.normalizeMimeType(
                tuple.format.mimeType
                    .substringBefore(';')
                    .trim(),
            )
        val sampleMime =
            when (tuple.format.type) {
                FormatType.AUDIO -> MimeTypes.getAudioMediaMimeType(tuple.format.codecs)
                FormatType.VIDEO -> MimeTypes.getVideoMediaMimeType(tuple.format.codecs)
            }
        if (container !in
            setOf(MimeTypes.AUDIO_MP4, MimeTypes.VIDEO_MP4, MimeTypes.APPLICATION_MP4, MimeTypes.AUDIO_WEBM, MimeTypes.VIDEO_WEBM) ||
            sampleMime == null
        ) {
            throw IOException("Unsupported SABR codec or container")
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
