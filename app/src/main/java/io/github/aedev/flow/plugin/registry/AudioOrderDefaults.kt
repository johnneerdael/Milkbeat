package io.github.aedev.flow.plugin.registry

private const val BEATPORT_PLUGIN_ID = "nl.neerdael.beatport"

// Beatport streams only for subscription accounts and its catalog rarely holds other services'
// recordings, so it plays its own tracks unless the listener adds it to the audio order.
private val OWN_TRACKS_ONLY_BY_DEFAULT = setOf(BEATPORT_PLUGIN_ID)

private const val BEATPORT_OWN_TRACKS_ONLY = "audio-order-beatport-own-tracks-only"

internal fun joinsAudioOrderByDefault(pluginId: String): Boolean = pluginId !in OWN_TRACKS_ONLY_BY_DEFAULT

/** Enabled audio plugins outside the listener's order: each plays only tracks from its own service. */
internal fun ownTracksOnlyAudio(
    plugins: List<InstalledPlugin>,
    selection: ProviderSelection,
): List<InstalledPlugin> = plugins.filter { it.enabled && it.manifest.roles.audio != null && it.id !in selection.audio }

/** [state] after the one-time audio order changes it has not had yet; each runs once, so the listener can undo it. */
internal fun withAudioOrderMigrations(state: PluginRegistryState): PluginRegistryState {
    if (BEATPORT_OWN_TRACKS_ONLY in state.migrations) return state
    return state.copy(
        selection = state.selection.copy(audio = state.selection.audio - BEATPORT_PLUGIN_ID),
        migrations = state.migrations + BEATPORT_OWN_TRACKS_ONLY,
    )
}
