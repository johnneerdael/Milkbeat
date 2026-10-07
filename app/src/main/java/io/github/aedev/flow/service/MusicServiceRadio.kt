package io.github.aedev.flow.service

import android.util.Log
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicRadioPlanner
import io.github.aedev.flow.player.MusicVideoItems
import io.github.aedev.flow.player.replaceFutureRadio
import io.github.aedev.flow.plugin.playback.RadioFilterSelection
import io.github.aedev.flow.plugin.playback.RadioPage
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor

private const val TAG = "Media3MusicService"
private const val RADIO_MIN_UPCOMING = 3
private const val RADIO_APPEND_BATCH = 10
private const val RADIO_POOL_LOW_WATER = 15

/**
 * Decides whether this PLAYLIST_CHANGED is a real new queue (reseed the
 * radio) or just an in-queue skip routed through playTrack (extend only).
 */
internal fun Media3MusicService.onQueueContextChanged(currentId: String) {
    val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
    val queueIds = manager.queue.value.map { it.videoId }
    val explicitSeedId = manager.pendingRadioSeedId
    manager.pendingRadioSeedId = null
    val collectionId = manager.pendingRadioPlaylistId
    manager.pendingRadioPlaylistId = null

    val context =
        MusicRadioPlanner.resolveQueueContext(
            currentId = currentId,
            queueIds = queueIds,
            previousIds = lastQueueIds,
            explicitSeedId = explicitSeedId,
            collectionRequested = collectionId != null,
        )
    lastQueueIds = context.knownIds
    if (!context.reseed) {
        maybeExtendRadio()
        return
    }
    radioGeneration++
    radioTuning.reset(radioGeneration)
    val seedId = currentId
    manager.queueCollectionState.value = manager.currentTrack.value
        ?.playbackContext
        ?.sourceCollectionId ?: collectionId
    radioSeedId = seedId
    radioContinuation = null
    radioPage = null
    radioResumeWhenAppended = false
    explicitRadioRequest = context.explicit
    automixJob?.cancel()
    radioTopUpJob?.cancel()
    manager.updateAutomixItems(emptyList())
    radioCollectionId =
        manager.currentTrack.value
            ?.playbackContext
            ?.takeIf { it.preferCollectionRadio }
            ?.radioCollectionId
            ?.takeUnless { context.explicit }
    radioSeedPending = true
    manager.setRadioLoading(false)
    if (player.isPlaying) startPendingRadio()
}

internal fun Media3MusicService.startPendingRadio() {
    if (!radioSeedPending) return
    val id = player.currentMediaItem?.mediaId ?: return
    radioSeedPending = false
    radioSeedId = id
    startRadio(id)
}

/**
 * Seeds the station the way YouTube Music does, from the first of [MusicRadioPlanner.radioSeeds] that
 * gives one, kept in the order YouTube built it. Only artists the user blocked or turned down are
 * taken out.
 */
internal fun Media3MusicService.startRadio(currentId: String) {
    automixJob?.cancel()
    radioTopUpJob?.cancel()
    val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
    // A new session never inherits the last one's suggestions, even when this fetch fails.
    manager.updateAutomixItems(emptyList())
    manager.setRadioLoading(true)
    val generation = radioGeneration
    val seeds =
        MusicRadioPlanner.radioSeeds(
            mirrorPlaylistId = radioCollectionId,
            queueIds =
                manager.queue.value
                    .map { it.videoId },
            currentId = currentId,
        )
    automixJob =
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val seeded =
                    MusicRadioPlanner.firstStation(
                        seeds,
                        page = { candidate ->
                            when (candidate) {
                                is MusicRadioPlanner.RadioSeed.Playlist -> {
                                    mix(EntityRef(EntityKind.PLAYLIST, candidate.id))
                                }

                                is MusicRadioPlanner.RadioSeed.Track -> {
                                    mix(
                                        EntityRef(EntityKind.TRACK, candidate.id),
                                        queuedDescriptor(candidate.id),
                                    )
                                }
                            }
                        },
                        station = { candidate, page ->
                            val seedId = (candidate as? MusicRadioPlanner.RadioSeed.Track)?.id ?: currentId
                            radioModeTuner.withoutHiddenArtists(toRadioTracks(page, seedId))
                        },
                    )
                val seed = seeded?.seed
                val result = seeded?.page
                val station = seeded?.tracks.orEmpty()
                withContext(Dispatchers.Main) {
                    if (generation != radioGeneration) return@withContext
                    // A top-up whose mix ran out reseeds from anything but this, so it names the track actually used.
                    radioSeedId = (seed as? MusicRadioPlanner.RadioSeed.Track)?.id ?: currentId
                    radioContinuation = result?.tracks?.next
                    radioPage = result
                    radioTuning.station(result, generation)
                    Log.i(
                        TAG,
                        "Radio seeded from $seed via ${result?.pluginId}: ${station.size} tracks, " +
                            "presets=${result
                                ?.tracks
                                ?.filters
                                ?.options
                                ?.size ?: 0}, continuation=${radioContinuation != null}",
                    )
                    if (station.isNotEmpty()) {
                        manager.updateAutomixItems(station)
                        maybeExtendRadio()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error seeding radio", e)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (generation == radioGeneration) manager.setRadioLoading(false)
                }
            }
        }
}

/** The first page of the radio seeded from [seed], from the plugins; null when none can build one. */
internal suspend fun Media3MusicService.mix(
    seed: EntityRef,
    seedTrack: TrackDescriptor? = null,
): RadioPage? =
    try {
        pluginRadio.page(seed, seedTrack)
    } catch (e: PluginCallException) {
        Log.w(TAG, "Radio from ${seed.providerId} unavailable: ${e.error.message}")
        null
    }

/** The next page of [previous]; null when its plugin has none to give. */
internal suspend fun Media3MusicService.mixNext(
    previous: RadioPage,
    cursor: String,
): RadioPage? =
    try {
        pluginRadio.next(previous, cursor)
    } catch (e: PluginCallException) {
        Log.w(TAG, "Radio from ${previous.seed.providerId} ended: ${e.error.message}")
        null
    }

/** How the queued or playing track [id] is described, so its radio can be seeded in another plugin. */
internal fun Media3MusicService.queuedDescriptor(id: String): TrackDescriptor? {
    val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
    return (listOfNotNull(manager.currentTrack.value) + manager.queue.value)
        .firstOrNull { it.videoId == id }
        ?.let(MusicVideoItems::descriptor)
}

private fun Media3MusicService.toRadioTracks(
    page: RadioPage,
    seedId: String?,
): List<MusicTrack> =
    radioModeTuner.eligibleTracks(
        page = page,
        tracks = radioModeTuner.tracks(page),
        queue = EnhancedMusicPlayerManager.queue.value,
        seedId = seedId,
    )

internal fun Media3MusicService.switchRadioMode(selection: RadioFilterSelection) {
    if (selection.generation != radioGeneration || !radioTuning.valid(selection)) return
    radioGeneration++
    val generation = radioGeneration
    automixJob?.cancel()
    radioTopUpJob?.cancel()
    val manager = EnhancedMusicPlayerManager
    automixJob =
        radioModeTuner.launch(
            scope = lifecycleScope,
            selection = selection,
            generation = generation,
            isCurrent = { generation == radioGeneration },
            applyStation = { result, station ->
                manager.replaceFutureRadio()
                lastQueueIds = manager.queue.value.map { it.videoId }
                manager.updateAutomixItems(radioModeTuner.eligibleTracks(result, station, manager.queue.value))
                radioPage = result
                radioContinuation = result.tracks.next
                radioTuning.reset(generation)
                radioTuning.station(result, generation)
                maybeExtendRadio()
            },
            setLoading = manager::setRadioLoading,
        )
}

/**
 * Called on ordinary advances (main thread). Moves the next few pool tracks
 * into the REAL queue when it runs short — the queue only ever grows, so
 * nothing the user sees is replaced — and refills the pool in the background.
 */
internal fun Media3MusicService.maybeExtendRadio() {
    if (radioSeedPending) return
    if (!radioAutoplayEnabled && !explicitRadioRequest) return
    if (!playerInitialized) return
    // Repeat already produces an endless queue — matching desktop.
    if (player.repeatMode != Player.REPEAT_MODE_OFF) return
    val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
    val ended = player.playbackState == Player.STATE_ENDED
    // Shuffle keeps meaning "shuffle MY queue" while it plays, but once the
    // shuffled queue is exhausted the radio still has to carry on.
    if (manager.shuffleEnabled.value && !ended) return

    // At ENDED every item has played, whatever the timeline says (shuffle).
    val remaining = player.mediaItemCount - player.currentMediaItemIndex - 1
    if (!ended && remaining > RADIO_MIN_UPCOMING) return

    val queueIds = manager.queue.value.mapTo(HashSet()) { it.videoId }
    val batch = MusicRadioPlanner.nextBatch(manager.automixItems.value, queueIds, RADIO_APPEND_BATCH)
    if (ended && batch.isNotEmpty() && !radioResumeWhenAppended) {
        radioResumeWhenAppended = true
        radioEndedItemCount = player.mediaItemCount
    }
    batch.forEach { track ->
        manager.addToQueue(track)
        manager.removeAutomixItem(track.videoId)
    }
    if (batch.isNotEmpty()) {
        // Our own growth must not read as a new queue on the next skip.
        lastQueueIds = manager.queue.value.map { it.videoId }
        Log.d(TAG, "Radio appended ${batch.size} tracks to the queue")
    }
    // A dead-ended queue with nothing appendable needs a fetch regardless of
    // pool size — the pool may be all duplicates of what already played.
    if (manager.automixItems.value.size < RADIO_POOL_LOW_WATER || (ended && batch.isEmpty())) extendRadioPool()
}

/** Fetch the next radio page and APPEND it to the pool — never replaces. */
internal fun Media3MusicService.extendRadioPool() {
    if (radioTopUpJob?.isActive == true || automixJob?.isActive == true) return
    val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
    manager.setRadioLoading(true)
    val generation = radioGeneration
    radioTopUpJob =
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val previous = radioPage
                val continuation = radioContinuation
                val result =
                    if (previous != null && continuation != null) {
                        mixNext(previous, continuation)
                    } else {
                        // The mix ran out: carry on with the mix of what is playing now, as
                        // YouTube Music does, rather than drifting from the far end of the pool.
                        val seedId =
                            listOfNotNull(manager.currentTrack.value, manager.queue.value.lastOrNull())
                                .map { it.videoId }
                                .firstOrNull { it != radioSeedId && !LocalMediaIds.isLocal(it) }
                                ?: return@launch
                        val current =
                            withContext(Dispatchers.Main) {
                                if (generation == radioGeneration) {
                                    radioSeedId = seedId
                                    true
                                } else {
                                    false
                                }
                            }
                        if (!current) return@launch
                        mix(EntityRef(EntityKind.TRACK, seedId), queuedDescriptor(seedId))
                    } ?: return@launch
                val station = radioModeTuner.withoutHiddenArtists(toRadioTracks(result, seedId = null))
                withContext(Dispatchers.Main) {
                    if (generation != radioGeneration) return@withContext
                    radioContinuation = result.tracks.next
                    radioPage = result
                    radioTuning.station(result, generation)
                    Log.d(TAG, "Radio pool topped up with ${station.size} tracks, continuation=${radioContinuation != null}")
                    if (station.isNotEmpty()) {
                        manager.appendAutomixItems(station)
                        maybeExtendRadio()
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Radio top-up failed: ${e.message}")
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    if (generation == radioGeneration) manager.setRadioLoading(false)
                }
            }
        }
}
