package io.github.aedev.flow.plugin.playback

import android.util.Log
import io.github.aedev.flow.data.local.dao.TrackMatchDao
import io.github.aedev.flow.data.local.entity.TrackMatchEntity
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioMatchStrategy
import nl.neerdael.milkbeat.plugin.MatchAudioRequest
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
        private val registry: PluginRegistry,
    ) {
        private val inFlight = ConcurrentHashMap<String, CompletableDeferred<TrackDescriptor?>>()
        private val pruned = AtomicBoolean(false)

        /** [pluginId]'s track for [track], or null when it has none it is confident of. */
        suspend fun match(
            track: TrackDescriptor,
            pluginId: String,
            excludedId: String? = null,
            strategy: AudioMatchStrategy = AudioMatchStrategy.SONGS,
            onProgress: (TrackMatchProgress) -> Unit = {},
        ): TrackDescriptor? =
            try {
                find(track, pluginId, excludedId, strategy, onProgress)
            } catch (e: PluginCallException) {
                Log.w(TAG, "$pluginId could not search for ${track.title}: ${e.error.message}")
                null
            }

        private suspend fun find(
            track: TrackDescriptor,
            pluginId: String,
            excludedId: String?,
            strategy: AudioMatchStrategy,
            onProgress: (TrackMatchProgress) -> Unit = {},
        ): TrackDescriptor? {
            val fingerprint = fingerprint(track)
            validatedCache(track, fingerprint, pluginId, strategy)?.let {
                if (excludedId == null || it.candidate?.ref?.providerId != excludedId) {
                    if (it.candidate != null) onProgress(TrackMatchProgress.SAVED_MATCH)
                    return it.candidate
                }
            }
            val key = "$pluginId|$fingerprint|${strategy.name}|${excludedId.orEmpty()}"
            val mine = CompletableDeferred<TrackDescriptor?>()
            inFlight.putIfAbsent(key, mine)?.let { owner ->
                onProgress(TrackMatchProgress.WAITING)
                try {
                    return owner.await()
                } catch (e: CancellationException) {
                    currentCoroutineContext().ensureActive()
                    inFlight.remove(key, owner)
                    return find(track, pluginId, excludedId, strategy, onProgress)
                }
            }
            try {
                onProgress(TrackMatchProgress.SEARCHING)
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

        suspend fun invalidate(
            track: TrackDescriptor,
            pluginId: String,
        ) {
            matches.delete(fingerprint(track), pluginId)
            val version = versionOf(pluginId)
            for (strategy in AudioMatchStrategy.entries) {
                matches.delete(missFingerprint(fingerprint(track), strategy, version), pluginId)
                matches.delete(batchMissFingerprint(fingerprint(track), strategy, version), pluginId)
            }
        }

        // A miss belongs to the plugin version that searched: an update may find what it could not.
        private fun versionOf(pluginId: String): Int =
            registry.state.value
                .plugin(pluginId)
                ?.manifest
                ?.versionCode ?: 0

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
            val version = versionOf(pluginId)
            val hit =
                cached(fingerprint, pluginId)?.takeIf { it.candidate != null }
                    ?: cached(missFingerprint(fingerprint, strategy, version), pluginId)
                    ?: cached(batchMissFingerprint(fingerprint, strategy, version), pluginId)
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
            val version = versionOf(pluginId)
            if (best != null) {
                for (scope in AudioMatchStrategy.entries) {
                    matches.delete(batchMissFingerprint(fingerprint, scope, version), pluginId)
                    matches.delete(missFingerprint(fingerprint, scope, version), pluginId)
                }
            }
            matches.upsert(
                TrackMatchEntity(
                    fingerprint =
                        if (best == null) missFingerprint(fingerprint, strategy, version) else fingerprint,
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
                pluginVersion: Int,
            ): String = "miss:${TrackMatchScore.POLICY_VERSION}:v$pluginVersion:${searchFingerprint(fingerprint, strategy)}"

            private fun batchMissFingerprint(
                fingerprint: String,
                strategy: AudioMatchStrategy,
                pluginVersion: Int,
            ): String = "batch-miss:${TrackMatchScore.POLICY_VERSION}:v$pluginVersion:${searchFingerprint(fingerprint, strategy)}"

            /** A track's identity across plugins: its ISRC when it has one, else every id it carries. */
            internal fun fingerprint(track: TrackDescriptor): String =
                track.ids["isrc"]?.takeIf { it.isNotBlank() }?.let { "isrc:${it.uppercase()}" }
                    ?: track.ids.entries
                        .sortedBy { it.key }
                        .joinToString(",") { "${it.key}:${it.value}" }
                        .ifEmpty { "ref:${track.ref.kind}:${track.ref.providerId}" }
        }
    }
