package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioMatchStrategy

internal fun InstalledPlugin.audioMatchStrategy(): AudioMatchStrategy =
    if (manifest.roles.audio?.musicVideo == true) AudioMatchStrategy.VIDEOS else AudioMatchStrategy.SONGS

internal data class AudioProviderAttempt(
    val plugin: InstalledPlugin,
    val direct: TrackDescriptor?,
)

internal fun audioProviderAttempts(
    state: PluginRegistryState,
    track: TrackDescriptor,
    withPicture: Boolean = false,
    preferredProviderId: String? = null,
): List<AudioProviderAttempt> =
    (listOfNotNull(preferredProviderId) + state.selection.audio).distinct().mapNotNull { id ->
        val plugin = state.plugin(id) ?: return@mapNotNull null
        val role = plugin.manifest.roles.audio ?: return@mapNotNull null
        if (withPicture && !role.musicVideo) return@mapNotNull null
        val direct = directAudioTrack(track, plugin)
        if (direct == null && (!role.match || track.title.isBlank())) return@mapNotNull null
        AudioProviderAttempt(plugin, direct)
    }

internal fun directAudioTrack(
    track: TrackDescriptor,
    plugin: InstalledPlugin,
): TrackDescriptor? {
    val spaces =
        plugin.manifest.roles.audio
            ?.idSpaces
            .orEmpty()
    val own = track.ids.entries.firstOrNull { it.key in spaces && it.value.isNotBlank() } ?: return null
    return if (track.ref.providerId == own.value) track else track.copy(ref = track.ref.copy(providerId = own.value))
}
