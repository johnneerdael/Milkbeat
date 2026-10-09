package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ownTracksOnlyAudio
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioMatchStrategy

internal fun InstalledPlugin.audioMatchStrategy(): AudioMatchStrategy =
    if (manifest.roles.audio?.musicVideo == true) AudioMatchStrategy.VIDEOS else AudioMatchStrategy.SONGS

internal data class AudioProviderAttempt(
    val plugin: InstalledPlugin,
    val direct: TrackDescriptor?,
)

/**
 * The providers to try for [track], in order: the playback context's [preferredProviderId], then
 * enabled audio plugins outside the listener's order (these play only tracks whose source they are),
 * then the listener's order, which every track follows, including tracks from the providers in it.
 */
internal fun audioProviderAttempts(
    state: PluginRegistryState,
    track: TrackDescriptor,
    withPicture: Boolean = false,
    preferredProviderId: String? = null,
): List<AudioProviderAttempt> {
    val sortable = state.selection.audio.distinct()
    val ownTracksOnly = ownTracksOnlyAudio(state.plugins, state.selection).map { it.id }
    return (listOfNotNull(preferredProviderId) + ownTracksOnly + sortable).distinct().mapNotNull { id ->
        val plugin = state.plugin(id) ?: return@mapNotNull null
        val role = plugin.manifest.roles.audio ?: return@mapNotNull null
        if (withPicture && !role.musicVideo) return@mapNotNull null
        val direct = directAudioTrack(track, plugin)
        if (id !in sortable && id != preferredProviderId) {
            // An alias in another service's track must not let an own-tracks provider jump the order.
            return@mapNotNull AudioProviderAttempt(plugin, direct).takeIf { direct?.ref == track.ref }
        }
        if (direct == null && (!role.match || track.title.isBlank())) return@mapNotNull null
        AudioProviderAttempt(plugin, direct)
    }
}

/** Whether no provider in [attempts] plays this track's own source, as for Spotify metadata. */
internal fun TrackDescriptor.hasNoPlayingSource(attempts: List<AudioProviderAttempt>): Boolean = attempts.none { it.direct?.ref == ref }

internal fun directAudioTrack(
    track: TrackDescriptor,
    plugin: InstalledPlugin,
): TrackDescriptor? {
    val spaces =
        plugin.manifest.roles.audio
            ?.idSpaces
            .orEmpty()
    val own =
        track.ids.entries.firstOrNull { it.key in spaces && it.value == track.ref.providerId && it.value.isNotBlank() }
            ?: track.ids.entries.firstOrNull { it.key in spaces && it.value.isNotBlank() } ?: return null
    return if (track.ref.providerId == own.value) track else track.copy(ref = track.ref.copy(providerId = own.value))
}
