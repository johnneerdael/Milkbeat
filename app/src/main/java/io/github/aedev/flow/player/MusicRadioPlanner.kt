package io.github.aedev.flow.player

import io.github.aedev.flow.data.music.model.MusicTrack

/**
 * Decides whether a playlist change opens a new radio session or continues the current one.
 *
 * The queue alone cannot tell the two apart: an in-queue skip and an explicit "Start radio" both
 * arrive as PLAYLIST_CHANGED, and a radio seeded from the playing track always looks like the
 * session it is meant to replace. An explicit request therefore carries its own seed.
 */
internal object MusicRadioPlanner {
    data class QueueContext(
        val reseed: Boolean,
        val explicit: Boolean,
        val knownIds: List<String>,
    )

    fun resolveQueueContext(
        currentId: String,
        queueIds: List<String>,
        previousIds: List<String>?,
        explicitSeedId: String?,
        collectionRequested: Boolean = false,
    ): QueueContext {
        if (explicitSeedId == currentId || collectionRequested) {
            return QueueContext(reseed = true, explicit = explicitSeedId == currentId, knownIds = queueIds)
        }

        val previous =
            previousIds
                ?: return QueueContext(reseed = true, explicit = false, knownIds = queueIds)

        // Same session when the track was already part of the previous queue: skips and queue
        // jumps rebuild the playlist (sometimes with a pruned list), but the user never left
        // their queue — only a track from OUTSIDE it reseeds.
        val sameContext = previous == queueIds || currentId in previous
        if (!sameContext) {
            return QueueContext(reseed = true, explicit = false, knownIds = queueIds)
        }

        // A pruned rebuild (stale mirror) must not shrink the known context.
        val knownIds = if (queueIds.size < previous.size) (previous + queueIds).distinct() else queueIds
        return QueueContext(reseed = false, explicit = false, knownIds = knownIds)
    }

    /** What a station is built from: the private YouTube copy of a mirrored playlist, or a track. */
    sealed interface RadioSeed {
        data class Playlist(
            val id: String,
        ) : RadioSeed

        data class Track(
            val id: String,
        ) : RadioSeed
    }

    /**
     * The seeds to try, in order, until one gives a station: a mirrored playlist always seeds from its
     * YouTube copy, anything else from the first track of the queue. The playing track closes the
     * list, so whatever plays always gets a radio.
     */
    fun radioSeeds(
        mirrorPlaylistId: String?,
        queueIds: List<String>,
        currentId: String,
    ): List<RadioSeed> =
        listOfNotNull(
            mirrorPlaylistId?.let(RadioSeed::Playlist),
            queueIds.firstOrNull()?.let(RadioSeed::Track),
            RadioSeed.Track(currentId),
        ).distinct()

    data class SeededStation<P, T>(
        val seed: RadioSeed,
        val page: P,
        val tracks: List<T>,
    )

    /**
     * Tries [seeds] in order and keeps the first whose [station] still has a track once the queue and
     * hidden artists are taken out: a page made only of songs already queued must not end the search.
     * When none has one, the first page that came back is kept for its continuation and presets.
     */
    suspend fun <P : Any, T> firstStation(
        seeds: List<RadioSeed>,
        page: suspend (RadioSeed) -> P?,
        station: suspend (RadioSeed, P) -> List<T>,
    ): SeededStation<P, T>? {
        var fallback: SeededStation<P, T>? = null
        for (seed in seeds) {
            val result = page(seed) ?: continue
            val seeded = SeededStation(seed, result, station(seed, result))
            if (seeded.tracks.isNotEmpty()) return seeded
            if (fallback == null) fallback = seeded
        }
        return fallback
    }

    /**
     * The list under the toggle is the up-next buffer, so it is ordered once here and consumed
     * from the head. Ordering it for display and re-ordering it again at append time is what made
     * the queue fill with tracks other than the ones on screen.
     */
    const val MAX_POOL_SIZE = 100

    /** The pool a fresh station starts with, in the order YouTube built the mix. */
    fun seedPool(
        candidates: List<MusicTrack>,
        currentId: String?,
        queueIds: Set<String>,
    ): List<MusicTrack> =
        candidates
            .distinctBy { it.videoId }
            .filterNot { it.videoId == currentId || it.videoId in queueIds }
            .take(MAX_POOL_SIZE)

    /** Grows the pool without disturbing what is already in it, and without letting it run away. */
    fun growPool(
        existing: List<MusicTrack>,
        incoming: List<MusicTrack>,
        currentId: String?,
        queueIds: Set<String>,
    ): List<MusicTrack> {
        val room = MAX_POOL_SIZE - existing.size
        if (room <= 0) return existing
        val existingIds = existing.mapTo(HashSet()) { it.videoId }
        val fresh =
            incoming
                .distinctBy { it.videoId }
                .filterNot { it.videoId == currentId || it.videoId in queueIds || it.videoId in existingIds }
                .take(room)
        return if (fresh.isEmpty()) existing else existing + fresh
    }

    /** What the queue takes next: the head of the list the user is looking at, in that order. */
    fun nextBatch(
        pool: List<MusicTrack>,
        queueIds: Set<String>,
        limit: Int,
    ): List<MusicTrack> = pool.filterNot { it.videoId in queueIds }.take(limit)
}
