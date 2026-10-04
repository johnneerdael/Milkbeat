package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.PluginError

data class AudioBatchIndexingResult(
    val matches: List<TrackDescriptor?>,
    val playlist: PrivatePlaylistImportResult? = null,
    val playlistError: PluginError? = null,
    val errors: List<PluginError?> = emptyList(),
)
