package io.github.aedev.flow.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import io.github.aedev.flow.data.folders.MusicFolderKind

/** Where the playing sound comes from, as the now-playing quality line names it. */
sealed interface AudioQualitySource {
    /** An audio plugin, by the name its manifest gives. */
    data class Provider(
        val name: String,
    ) : AudioQualitySource

    data object Local : AudioQualitySource

    data class Folder(
        val kind: MusicFolderKind,
    ) : AudioQualitySource

    /** A provider track that plays from the offline download instead of its stream. */
    data object Download : AudioQualitySource
}

enum class AudioCodec(
    val lossless: Boolean = false,
) {
    FLAC(lossless = true),
    ALAC(lossless = true),
    PCM(lossless = true),
    TRUEHD(lossless = true),
    MP3,
    MP2,
    AAC,
    HE_AAC,
    HE_AAC_V2,
    XHE_AAC,
    OPUS,
    VORBIS,
    AC3,
    EAC3,
    EAC3_JOC,
    AC4,
    DTS,
    DTS_HD,
    MPEG_H,
    IAMF,
}

/** The audio the music player decodes for [playbackId]; any part the stream does not reveal is null. */
data class PlaybackAudioQuality(
    val playbackId: String,
    val source: AudioQualitySource?,
    val codec: AudioCodec?,
    /** The codec's name from its MIME subtype when [codec] does not know it. */
    val otherCodec: String?,
    val bitrateKbps: Int?,
    val sampleRateHz: Int?,
    val bitDepth: Int?,
)

/** What the plugin said it delivers, for what the container itself leaves out. */
data class DeclaredAudio(
    val mimeType: String?,
    val codecs: String?,
    val bitrate: Int?,
)

/**
 * The quality of [format], the selected audio track of the playing item. A lossless codec shows its
 * decoded rate (44.1 kHz × 16 bit × 2 channels = 1411 kb/s, as providers label CD quality), whatever
 * its compressed size; otherwise the track's own bitrate wins over the plugin's [declared] one.
 */
@OptIn(UnstableApi::class)
fun playbackAudioQuality(
    playbackId: String,
    source: AudioQualitySource?,
    format: Format?,
    declared: DeclaredAudio?,
): PlaybackAudioQuality? {
    if (format == null && declared == null) return null
    val mime = format?.sampleMimeType ?: declared?.mimeType?.audioSampleMime()
    val codecs = format?.codecs ?: declared?.codecs
    val codec = audioCodec(mime, codecs)
    val sampleRate = format?.sampleRate?.known()
    val channels = format?.channelCount?.known()
    val bitDepth =
        format
            ?.pcmEncoding
            ?.takeIf { codec?.lossless == true && it != Format.NO_VALUE && Util.isEncodingLinearPcm(it) }
            ?.let { Util.getByteDepth(it) * Byte.SIZE_BITS }
    val decodedRate =
        if (codec?.lossless == true && sampleRate != null && channels != null && bitDepth != null) {
            sampleRate * bitDepth * channels
        } else {
            null
        }
    val bitrate =
        decodedRate
            ?: format?.averageBitrate?.known()
            ?: format?.bitrate?.known()
            ?: declared?.bitrate?.takeIf { it > 0 }
    return PlaybackAudioQuality(
        playbackId = playbackId,
        source = source,
        codec = codec,
        otherCodec = if (codec == null) mime?.substringAfter('/')?.takeIf(String::isNotBlank)?.uppercase() else null,
        bitrateKbps = bitrate?.let { (it + 500) / 1_000 },
        sampleRateHz = sampleRate,
        bitDepth = bitDepth,
    )
}

/**
 * Names the source of a queue item by its URI scheme, or by the plugin that resolved it. A file or
 * content URI under a provider track's id, not a local library id, is that track's finished download.
 */
fun audioQualitySource(
    scheme: String?,
    localLibraryItem: Boolean,
    providerName: String?,
): AudioQualitySource? {
    MusicFolderKind.forScheme(scheme)?.let { return AudioQualitySource.Folder(it) }
    return when {
        scheme == "content" || scheme == "file" -> if (localLibraryItem) AudioQualitySource.Local else AudioQualitySource.Download
        providerName != null -> AudioQualitySource.Provider(providerName)
        else -> null
    }
}

private fun Int.known(): Int? = takeIf { it != Format.NO_VALUE && it > 0 }

// A plugin's container type (audio/mp4, audio/webm) says nothing about the codec inside it.
private fun String.audioSampleMime(): String? =
    substringBefore(';').trim().lowercase().takeUnless {
        it in setOf(MimeTypes.AUDIO_MP4, MimeTypes.AUDIO_WEBM, MimeTypes.AUDIO_OGG, MimeTypes.AUDIO_MATROSKA) ||
            !it.startsWith("audio/")
    }

private fun audioCodec(
    mime: String?,
    codecs: String?,
): AudioCodec? =
    when (mime) {
        MimeTypes.AUDIO_FLAC -> AudioCodec.FLAC
        MimeTypes.AUDIO_ALAC -> AudioCodec.ALAC
        MimeTypes.AUDIO_RAW, MimeTypes.AUDIO_WAV -> AudioCodec.PCM
        MimeTypes.AUDIO_TRUEHD -> AudioCodec.TRUEHD
        MimeTypes.AUDIO_MPEG -> AudioCodec.MP3
        MimeTypes.AUDIO_MPEG_L2 -> AudioCodec.MP2
        MimeTypes.AUDIO_AAC -> aacProfile(codecs)
        MimeTypes.AUDIO_OPUS -> AudioCodec.OPUS
        MimeTypes.AUDIO_VORBIS -> AudioCodec.VORBIS
        MimeTypes.AUDIO_AC3 -> AudioCodec.AC3
        MimeTypes.AUDIO_E_AC3 -> AudioCodec.EAC3
        MimeTypes.AUDIO_E_AC3_JOC -> AudioCodec.EAC3_JOC
        MimeTypes.AUDIO_AC4 -> AudioCodec.AC4
        MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_EXPRESS -> AudioCodec.DTS
        MimeTypes.AUDIO_DTS_HD -> AudioCodec.DTS_HD
        MimeTypes.AUDIO_MPEGH_MHA1, MimeTypes.AUDIO_MPEGH_MHM1 -> AudioCodec.MPEG_H
        MimeTypes.AUDIO_IAMF -> AudioCodec.IAMF
        else -> null
    }

// RFC 6381 names AAC profiles by MPEG-4 audio object type: 5 is SBR, 29 is PS, 42 is USAC.
private fun aacProfile(codecs: String?): AudioCodec =
    when (
        codecs
            ?.split(',')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("mp4a.40.") }
            ?.removePrefix("mp4a.40.")
    ) {
        "5" -> AudioCodec.HE_AAC
        "29" -> AudioCodec.HE_AAC_V2
        "42" -> AudioCodec.XHE_AAC
        else -> AudioCodec.AAC
    }
