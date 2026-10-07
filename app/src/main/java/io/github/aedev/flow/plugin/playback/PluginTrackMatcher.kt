package io.github.aedev.flow.plugin.playback

import android.util.Log
import io.github.aedev.flow.data.local.dao.TrackMatchDao
import io.github.aedev.flow.data.local.entity.TrackMatchEntity
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioMatchStrategy
import nl.neerdael.milkbeat.plugin.MatchAudioBatchRequest
import nl.neerdael.milkbeat.plugin.MatchAudioRequest
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperations
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PluginTrackMatcher"
private val MATCH_LIFETIME_MS = TimeUnit.DAYS.toMillis(90)
private val MISS_LIFETIME_MS = TimeUnit.DAYS.toMillis(1)

/**
 * Finds an audio plugin's own track for a track another plugin describes (a Spotify track played
 * through YouTube Music): the plugin offers candidates, [TrackMatchScore] picks one, and the answer,
 * a miss included, is kept so each track is searched once. One lookup per track and plugin is in
 * flight at a time; a second caller waits for it.
 */
@Singleton
class PluginTrackMatcher
    @Inject
    constructor(
        private val host: PluginHost,
        private val matches: TrackMatchDao,
    ) {
        private val inFlight = ConcurrentHashMap<String, CompletableDeferred<TrackDescriptor?>>()
        private val pruned = AtomicBoolean(false)

        /** [pluginId]'s track for [track], or null when it has none it is confident of. */
        suspend fun match(
            track: TrackDescriptor,
            pluginId: String,
            excludedId: String? = null,
            strategy: AudioMatchStrategy = AudioMatchStrategy.SONGS,
        ): TrackDescriptor? =
            try {
                find(track, pluginId, excludedId, strategy)
            } catch (e: PluginCallException) {
                Log.w(TAG, "$pluginId could not search for ${track.title}: ${e.error.message}")
                null
            }

        private suspend fun find(
            track: TrackDescriptor,
            pluginId: String,
            excludedId: String?,
            strategy: AudioMatchStrategy,
        ): TrackDescriptor? {
            val fingerprint = fingerprint(track)
            validatedCache(track, fingerprint, pluginId, strategy)?.let {
                if (excludedId == null || it.candidate?.ref?.providerId != excludedId) return it.candidate
            }
            val key = "$pluginId|$fingerprint|${strategy.name}|${excludedId.orEmpty()}"
            val mine = CompletableDeferred<TrackDescriptor?>()
            inFlight.putIfAbsent(key, mine)?.let { owner ->
                try {
                    return owner.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    inFlight.remove(key, owner)
                    return find(track, pluginId, excludedId, strategy)
                }
            }
            try {
                return lookup(track, fingerprint, pluginId, excludedId, strategy).also(mine::complete)
            } catch (e: Throwable) {
                mine.completeExceptionally(e)
                throw e
            } finally {
                inFlight.remove(key, mine)
            }
        }

        suspend fun matchForIndexing(
            track: TrackDescriptor,
            pluginId: String,
            excludedId: String? = null,
            strategy: AudioMatchStrategy = AudioMatchStrategy.SONGS,
        ): TrackDescriptor? = find(track, pluginId, excludedId, strategy)

        suspend fun matchBatchForIndexing(
            tracks: List<TrackDescriptor>,
            pluginId: String,
            playlist: PrivatePlaylistImportRequest? = null,
            primaryStrategy: AudioMatchStrategy = AudioMatchStrategy.SONGS,
            ensureCallerActive: () -> Unit = {},
        ): AudioBatchIndexingResult {
            require(tracks.size <= 16) { "An indexing batch may contain at most 16 tracks" }
            val distinct = tracks.associateBy(::fingerprint)
            val resolved = mutableMapOf<String, TrackDescriptor?>()
            val errors = mutableMapOf<String, PluginError>()
            val owned = linkedMapOf<String, CompletableDeferred<TrackDescriptor?>>()
            val waiting = linkedMapOf<String, CompletableDeferred<TrackDescriptor?>>()
            val primaryMisses = mutableSetOf<String>()
            var preparation: nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult? = null
            var preparationError: PluginError? = null

            fun flightKey(fingerprint: String) = "$pluginId|$fingerprint|${primaryStrategy.name}|"

            suspend fun finish(
                fingerprint: String,
                best: TrackMatchScore.Scored?,
            ) {
                val cacheKey = if (best == null) batchMissFingerprint(fingerprint, primaryStrategy) else fingerprint
                resolved[fingerprint] = remember(cacheKey, pluginId, best, primaryStrategy)
                owned.getValue(fingerprint).complete(best?.candidate)
            }

            fun fail(
                fingerprint: String,
                error: PluginError,
            ) {
                errors[fingerprint] = error
                owned.getValue(fingerprint).completeExceptionally(PluginCallException(pluginId, error))
            }

            try {
                for ((fingerprint, track) in distinct) {
                    val hit = validatedCache(track, fingerprint, pluginId, primaryStrategy)
                    if (hit != null && (
                            hit.candidate != null || primaryStrategy == AudioMatchStrategy.VIDEOS ||
                                cached(batchMissFingerprint(fingerprint, primaryStrategy), pluginId) != null
                        )
                    ) {
                        resolved[fingerprint] = hit.candidate
                        continue
                    }
                    if (hit != null) primaryMisses += fingerprint
                    val mine = CompletableDeferred<TrackDescriptor?>()
                    val owner = inFlight.putIfAbsent(flightKey(fingerprint), mine)
                    if (owner == null) owned[fingerprint] = mine else waiting[fingerprint] = owner
                }
                var pending = owned.keys.filterNot { it in primaryMisses }
                val strategies = if (primaryStrategy == AudioMatchStrategy.VIDEOS) listOf(primaryStrategy) else AudioMatchStrategy.entries
                for (strategy in strategies) {
                    if (strategy == AudioMatchStrategy.ALTERNATE_SONGS) pending = pending + primaryMisses.filter { it in owned }
                    val ensure = playlist.takeIf { strategy == strategies.first() }
                    if (pending.isEmpty() && ensure == null) continue
                    ensureCallerActive()
                    currentCoroutineContext().ensureActive()
                    val response =
                        host.call(
                            pluginId,
                            PluginOperations.matchAudioBatch,
                            MatchAudioBatchRequest(pending.map(distinct::getValue), strategy, ensure),
                        )
                    currentCoroutineContext().ensureActive()
                    if (ensure != null) {
                        preparation = response.playlist
                        preparationError = response.playlistError
                    }
                    val countError =
                        if (response.matches.size != pending.size) {
                            PluginError(PluginErrorCode.UNAVAILABLE, "The provider returned an incomplete matching batch")
                        } else {
                            null
                        }
                    val unresolved = mutableListOf<String>()
                    for ((index, fingerprint) in pending.withIndex()) {
                        val item = response.matches.getOrNull(index)
                        val error = countError ?: item?.error
                        if (error != null) {
                            fail(fingerprint, error)
                            continue
                        }
                        val best = TrackMatchScore.best(distinct.getValue(fingerprint), checkNotNull(item).candidates)
                        if (best != null || strategy == AudioMatchStrategy.VIDEOS) {
                            finish(fingerprint, best)
                        } else {
                            unresolved += fingerprint
                        }
                    }
                    pending = unresolved
                    if (preparationError != null) {
                        owned.filterValues { !it.isCompleted }.keys.forEach { fail(it, checkNotNull(preparationError)) }
                        break
                    }
                }
                for ((fingerprint, owner) in waiting) {
                    if (preparationError != null) {
                        errors[fingerprint] = checkNotNull(preparationError)
                        continue
                    }
                    ensureCallerActive()
                    try {
                        val candidate = owner.await()
                        if (candidate != null || primaryStrategy == AudioMatchStrategy.VIDEOS ||
                            cached(batchMissFingerprint(fingerprint, primaryStrategy), pluginId) != null
                        ) {
                            resolved[fingerprint] = candidate
                        } else {
                            inFlight.remove(flightKey(fingerprint), owner)
                            val retry =
                                matchBatchForIndexing(
                                    listOf(distinct.getValue(fingerprint)),
                                    pluginId,
                                    primaryStrategy = primaryStrategy,
                                    ensureCallerActive = ensureCallerActive,
                                )
                            resolved[fingerprint] = retry.matches.single()
                            retry.errors.singleOrNull()?.let { errors[fingerprint] = it }
                        }
                    } catch (error: CancellationException) {
                        currentCoroutineContext().ensureActive()
                        inFlight.remove(flightKey(fingerprint), owner)
                        val retry =
                            matchBatchForIndexing(
                                listOf(distinct.getValue(fingerprint)),
                                pluginId,
                                primaryStrategy = primaryStrategy,
                                ensureCallerActive = ensureCallerActive,
                            )
                        resolved[fingerprint] = retry.matches.single()
                        retry.errors.singleOrNull()?.let { errors[fingerprint] = it }
                    } catch (error: PluginCallException) {
                        errors[fingerprint] = error.error
                    }
                }
                return AudioBatchIndexingResult(
                    matches = tracks.map { resolved[fingerprint(it)] },
                    playlist = preparation,
                    playlistError = preparationError,
                    errors = tracks.map { errors[fingerprint(it)] },
                )
            } catch (error: Throwable) {
                owned.values.forEach { if (!it.isCompleted) it.completeExceptionally(error) }
                throw error
            } finally {
                owned.forEach { (fingerprint, mine) -> inFlight.remove(flightKey(fingerprint), mine) }
            }
        }

        suspend fun invalidate(
            track: TrackDescriptor,
            pluginId: String,
        ) {
            matches.delete(fingerprint(track), pluginId)
            for (strategy in AudioMatchStrategy.entries) {
                matches.delete(missFingerprint(fingerprint(track), strategy), pluginId)
                matches.delete(batchMissFingerprint(fingerprint(track), strategy), pluginId)
            }
        }

        private class Cached(
            val candidate: TrackDescriptor?,
        )

        private suspend fun cached(
            fingerprint: String,
            pluginId: String,
        ): Cached? {
            if (pruned.compareAndSet(false, true)) matches.deleteOlderThan(System.currentTimeMillis() - MATCH_LIFETIME_MS)
            val row = matches.find(fingerprint, pluginId) ?: return null
            val lifetime = if (row.candidate == null) MISS_LIFETIME_MS else MATCH_LIFETIME_MS
            if (System.currentTimeMillis() - row.matchedAt > lifetime) return null
            val candidate =
                row.candidate?.let { json ->
                    runCatching { PluginJson.decodeFromString(TrackDescriptor.serializer(), json) }.getOrNull() ?: return null
                }
            return Cached(candidate)
        }

        private suspend fun validatedCache(
            track: TrackDescriptor,
            fingerprint: String,
            pluginId: String,
            strategy: AudioMatchStrategy,
        ): Cached? {
            val hit =
                cached(fingerprint, pluginId)?.takeIf { it.candidate != null }
                    ?: cached(missFingerprint(fingerprint, strategy), pluginId)
                    ?: cached(batchMissFingerprint(fingerprint, strategy), pluginId)
                    ?: return null
            val candidate = hit.candidate ?: return hit
            if (TrackMatchScore.best(track, listOf(candidate)) != null) return hit
            matches.delete(fingerprint, pluginId)
            return null
        }

        private suspend fun lookup(
            track: TrackDescriptor,
            fingerprint: String,
            pluginId: String,
            excludedId: String?,
            strategy: AudioMatchStrategy,
        ): TrackDescriptor? {
            val result = host.call(pluginId, PluginOperations.matchAudio, MatchAudioRequest(track, strategy))
            result.error?.let { throw PluginCallException(pluginId, it) }
            val candidates = result.candidates
            val best = TrackMatchScore.best(track, candidates.filter { it.ref.providerId != excludedId })
            Log.d(
                TAG,
                "${track.title}: ${candidates.size} candidates from $pluginId, best ${best?.score?.let { "%.2f".format(it) } ?: "none"}",
            )
            if (best == null && excludedId != null) return null
            return remember(fingerprint, pluginId, best, strategy)
        }

        private suspend fun remember(
            fingerprint: String,
            pluginId: String,
            best: TrackMatchScore.Scored?,
            strategy: AudioMatchStrategy,
        ): TrackDescriptor? {
            if (best != null) {
                for (scope in AudioMatchStrategy.entries) {
                    matches.delete(batchMissFingerprint(fingerprint, scope), pluginId)
                    matches.delete(missFingerprint(fingerprint, scope), pluginId)
                }
            }
            matches.upsert(
                TrackMatchEntity(
                    fingerprint =
                        if (best == null && !fingerprint.startsWith("batch-miss:")) missFingerprint(fingerprint, strategy) else fingerprint,
                    pluginId = pluginId,
                    candidate = best?.candidate?.let { PluginJson.encodeToString(TrackDescriptor.serializer(), it) },
                    confidence = best?.score ?: 0.0,
                    matchedAt = System.currentTimeMillis(),
                ),
            )
            return best?.candidate
        }

        companion object {
            private fun searchFingerprint(
                fingerprint: String,
                strategy: AudioMatchStrategy,
            ): String = if (strategy == AudioMatchStrategy.SONGS) fingerprint else "${strategy.name}:$fingerprint"

            private fun missFingerprint(
                fingerprint: String,
                strategy: AudioMatchStrategy,
            ): String = "miss:${TrackMatchScore.POLICY_VERSION}:${searchFingerprint(fingerprint, strategy)}"

            private fun batchMissFingerprint(
                fingerprint: String,
                strategy: AudioMatchStrategy,
            ): String = "batch-miss:${TrackMatchScore.POLICY_VERSION}:${searchFingerprint(fingerprint, strategy)}"

            /** A track's identity across plugins: its ISRC when it has one, else every id it carries. */
            internal fun fingerprint(track: TrackDescriptor): String =
                track.ids["isrc"]?.takeIf { it.isNotBlank() }?.let { "isrc:${it.uppercase()}" }
                    ?: track.ids.entries
                        .sortedBy { it.key }
                        .joinToString(",") { "${it.key}:${it.value}" }
                        .ifEmpty { "ref:${track.ref.kind}:${track.ref.providerId}" }
        }
    }
