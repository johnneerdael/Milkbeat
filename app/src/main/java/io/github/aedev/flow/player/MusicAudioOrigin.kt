package io.github.aedev.flow.player

import android.os.Bundle
import androidx.media3.common.MediaItem
import io.github.aedev.flow.player.audio.DeclaredAudio
import io.github.aedev.flow.plugin.playback.ResolvedAudio

private const val AUDIO_DOWNLOAD = "milkbeat.audioDownload"
private const val AUDIO_PLUGIN = "milkbeat.audioPlugin"
private const val AUDIO_MIME = "milkbeat.audioMime"
private const val AUDIO_CODECS = "milkbeat.audioCodecs"
private const val AUDIO_BITRATE = "milkbeat.audioBitrate"
private val ORIGIN_KEYS = listOf(AUDIO_DOWNLOAD, AUDIO_PLUGIN, AUDIO_MIME, AUDIO_CODECS, AUDIO_BITRATE)

/** Where the resolver chose to play a provider track from: [pluginId]'s stream, or its download when null. */
internal data class MusicAudioOrigin(
    val pluginId: String?,
    val declared: DeclaredAudio?,
)

/**
 * The item as its resolver chose to play it, from [audio]'s plugin or, when null, the finished
 * download. Recorded when the source is built, so the window that plays carries the decision rather
 * than a later reading of the download index or the plugin's accepted streams.
 */
internal fun MediaItem.withAudioOrigin(audio: ResolvedAudio?): MediaItem {
    val extras =
        Bundle(mediaMetadata.extras ?: Bundle()).apply {
            // A rebuilt window, such as a refreshed stream, still carries the previous resolution's origin.
            ORIGIN_KEYS.forEach(::remove)
            if (audio == null) {
                putBoolean(AUDIO_DOWNLOAD, true)
                return@apply
            }
            val chosen = audio.stream.audioFormat
            putString(AUDIO_PLUGIN, audio.pluginId)
            putString(AUDIO_MIME, chosen?.mimeType ?: audio.stream.mimeType)
            (chosen?.codecs ?: audio.stream.codecs)?.let { putString(AUDIO_CODECS, it) }
            (chosen?.averageBitrate ?: chosen?.bitrate ?: audio.stream.bitrate)?.let { putInt(AUDIO_BITRATE, it) }
        }
    return buildUpon()
        .setMediaMetadata(
            mediaMetadata
                .buildUpon()
                .setExtras(extras)
                .build(),
        ).build()
}

/** What [withAudioOrigin] recorded; null for an item no resolver built. */
internal fun MediaItem.audioOrigin(): MusicAudioOrigin? {
    val extras = mediaMetadata.extras ?: return null
    if (extras.getBoolean(AUDIO_DOWNLOAD)) return MusicAudioOrigin(pluginId = null, declared = null)
    val pluginId = extras.getString(AUDIO_PLUGIN) ?: return null
    return MusicAudioOrigin(
        pluginId = pluginId,
        declared =
            DeclaredAudio(
                mimeType = extras.getString(AUDIO_MIME),
                codecs = extras.getString(AUDIO_CODECS),
                bitrate = extras.getInt(AUDIO_BITRATE).takeIf { extras.containsKey(AUDIO_BITRATE) },
            ),
    )
}
