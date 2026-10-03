package io.github.aedev.flow.ui.screens.music

import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.MusicPlaybackContext
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.plugin.catalog.toMusicTrack
import io.github.aedev.flow.plugin.mirror.MirrorRecord
import io.github.aedev.flow.plugin.mirror.PlaylistMirrorCoordinator
import io.github.aedev.flow.plugin.registry.PluginRegistry
import nl.neerdael.milkbeat.plugin.PluginJson
import javax.inject.Inject

internal data class MirrorPlayback(
    val track: MusicTrack,
    val queue: List<MusicTrack>,
    val startIndex: Int = selectedQueueIndex(track, queue),
)

class MirrorPlaybackPreparation
    @Inject
    constructor(
        private val mirrors: PlaylistMirrorCoordinator,
        private val registry: PluginRegistry,
    ) {
        internal suspend fun prepare(
            track: MusicTrack,
            queue: List<MusicTrack>,
            sourceId: String?,
            title: String,
            onWaiting: () -> Unit = {},
        ): MirrorPlayback {
            if (track.playbackContext != null || sourceId == null) return MirrorPlayback(track, queue)
            val source = ProviderEntityReference.decode(sourceId) ?: return MirrorPlayback(track, queue)
            val key = mirrors.selectedKey(source.pluginId, source.entity) ?: return MirrorPlayback(track, queue)
            val record = mirrors.prepareForPlayback(key, title, onWaiting)
            val space =
                registry.state.value
                    .plugin(key.targetPlugin)
                    ?.manifest
                    ?.roles
                    ?.metadata
                    ?.idSpace ?: error("Destination changed")
            val context =
                MusicPlaybackContext(
                    sourceId,
                    ProviderEntityReference.encode(key.targetPlugin, checkNotNull(record.destination)),
                    key.targetPlugin,
                )
            return prepareMirrorPlayback(record, track, queue, space, context)
        }
    }

internal fun prepareMirrorPlayback(
    record: MirrorRecord,
    requested: MusicTrack,
    queue: List<MusicTrack>,
    space: String,
    context: MusicPlaybackContext,
): MirrorPlayback {
    val sourceQueue =
        record.tracks.mapIndexed { index, descriptor ->
            val visible = queue.firstOrNull { it.sourcePosition == index }
            visible ?: descriptor.toMusicTrack(record.key.sourcePlugin).copy(sourcePosition = index)
        }
    val matches = record.matches.associateBy { it.sourcePosition }
    val prepared =
        sourceQueue
            .mapNotNull { source ->
                val match = matches[source.sourcePosition] ?: return@mapNotNull null
                val descriptor =
                    MusicVideoItems.descriptor(source).copy(
                        ids = MusicVideoItems.descriptor(source).ids + (space to match.destinationTrack.ref.providerId),
                    )
                source.copy(
                    descriptor =
                        PluginJson.encodeToString(
                            nl.neerdael.milkbeat.catalog.TrackDescriptor
                                .serializer(),
                            descriptor,
                        ),
                    playbackContext = context,
                    shuffleRequested = false,
                )
            }.let { if (requested.shuffleRequested) it.shuffled() else it }
    val requestedPosition =
        requested.sourcePosition
            ?: record.tracks.indexOfFirst { it.ref == MusicVideoItems.descriptor(requested).ref }.coerceAtLeast(0)
    val selected =
        prepared.firstOrNull { it.sourcePosition == requestedPosition }
            ?: prepared.filter { (it.sourcePosition ?: 0) >= requestedPosition }.minByOrNull { it.sourcePosition ?: 0 }
            ?: prepared.firstOrNull() ?: error("No playable matches")
    return MirrorPlayback(selected, prepared, prepared.indexOf(selected))
}

private fun selectedQueueIndex(
    track: MusicTrack,
    queue: List<MusicTrack>,
): Int {
    val index =
        queue.indexOfFirst {
            if (track.sourcePosition != null) it.sourcePosition == track.sourcePosition else it == track
        }
    return index.coerceAtLeast(0)
}

internal fun songRadioPlayback(track: MusicTrack): MirrorPlayback {
    val seed = track.copy(playbackContext = null, sourcePosition = null, shuffleRequested = false)
    return MirrorPlayback(seed, listOf(seed))
}
