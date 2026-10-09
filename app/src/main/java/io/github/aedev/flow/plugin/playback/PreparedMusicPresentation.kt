package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.player.resolver.AdaptiveDashManifest

/** Build picture track metadata without opening a progressive picture source. */
internal fun ResolvedAudio.buildPreparedDashManifest(): String? {
    val sound = stream.audioFormat ?: return null
    val picture = stream.video ?: return null
    if (stream.drm != null || sound.initRange == null || sound.indexRange == null) return null
    val audio = PluginVideoStreams.audioStreams(listOf(sound)).singleOrNull() ?: return null
    val durationMs = listOfNotNull(sound.durationMs, picture.durationMs, track.durationMs).maxOrNull() ?: 0L
    return AdaptiveDashManifest.build(PluginVideoStreams.videoStreams(listOf(picture)), audio, (durationMs + 999L) / 1000L)
}
