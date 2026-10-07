package io.github.aedev.flow.plugin.mirror

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.playback.AudioBatchIndexingResult
import io.github.aedev.flow.plugin.playback.PluginTrackMatcher
import io.github.aedev.flow.plugin.playback.TrackMatchScore
import io.github.aedev.flow.plugin.playback.audioMatchStrategy
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.plugin.runtime.retryingTransient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportMode
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportPhase
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportProgress
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportRequest
import nl.neerdael.milkbeat.catalog.PrivatePlaylistImportResult
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TracksRequest
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginOperations
import javax.inject.Inject

class PlaylistMirrorRunner
    @Inject
    constructor(
        private val host: PluginHost,
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
        private val matcher: PluginTrackMatcher,
        private val store: MirrorStorage,
        private val gate: MirrorExecutionGate = MirrorExecutionGate(),
        private val artworkLoader: MirrorArtwork? = null,
    ) {
        suspend fun prepare(
            key: MirrorKey,
            title: String,
            onProgress: suspend (PlaylistMirrorState) -> Unit = {},
            background: Boolean = false,
            artwork: Artwork? = null,
        ): MirrorRecord {
            val source = registry.state.value.plugin(key.sourcePlugin)
            val target = registry.state.value.plugin(key.targetPlugin)
            if (source
                    ?.manifest
                    ?.roles
                    ?.metadata
                    ?.personalCollections != true ||
                target
                    ?.manifest
                    ?.roles
                    ?.metadata
                    ?.privatePlaylistImport != true || target.manifest.roles.audio == null
            ) {
                throw MirrorPreparationException(MirrorFailure.UNSUPPORTED)
            }

            fun validate() {
                if ((accounts.accounts.value[key.sourcePlugin] as? ProviderAccount.SignedIn)?.key != key.sourceAccount ||
                    (accounts.accounts.value[key.targetPlugin] as? ProviderAccount.SignedIn)?.key != key.targetAccount
                ) {
                    throw MirrorPreparationException(MirrorFailure.ACCOUNT_CHANGED)
                }
                if (!samePackage(source, registry.state.value.plugin(key.sourcePlugin)) ||
                    !samePackage(target, registry.state.value.plugin(key.targetPlugin))
                ) {
                    throw MirrorPreparationException(MirrorFailure.PLUGIN_CHANGED)
                }
            }

            suspend fun active() {
                currentCoroutineContext().ensureActive()
                validate()
            }

            suspend fun read(): Pair<List<TrackDescriptor>, String?> {
                val tracks = mutableListOf<TrackDescriptor>()
                val cursors = mutableSetOf<String>()
                var cursor: String? = null
                var revision: String? = null
                do {
                    active()
                    val page =
                        retryingTransient(beforeRetry = { active() }) {
                            withContext(NonCancellable) {
                                host.call(key.sourcePlugin, PluginOperations.tracks, TracksRequest(key.source, cursor))
                            }
                        }
                    active()
                    if (cursor == null) revision = page.revision
                    if (page.revision != revision) throw MirrorPreparationException(MirrorFailure.SOURCE_CHANGED)
                    tracks += page.tracks
                    cursor = page.next
                    if (cursor != null && !cursors.add(cursor)) throw MirrorPreparationException(MirrorFailure.INVALID_PAGINATION)
                } while (cursor != null)
                return tracks to revision
            }

            active()
            val previous = store.get(key.id)
            val packages = mirrorPackageContext(source, target)
            if (!background) {
                previous.reusableReadyMirror(key, title, artwork, packages)?.let { ready ->
                    active()
                    if (ready !== previous) store.put(ready)
                    active()
                    onProgress(ready.readyState())
                    active()
                    return ready
                }
            }
            onProgress(PlaylistMirrorState(isPreparing = true))
            val (tracks, revision) = read()
            val sourceArtwork = artwork ?: previous?.takeIf { it.key == key }?.artwork
            val fingerprint =
                digest(
                    listOf(title, revision.orEmpty(), sourceArtwork?.url.orEmpty()) +
                        tracks.map { PluginJson.encodeToString(TrackDescriptor.serializer(), it) },
                )
            var record =
                previous?.takeIf { it.key == key && it.revision == fingerprint }
                    ?: MirrorRecord(key, title, fingerprint, tracks, destination = previous?.destination, artwork = sourceArtwork)

            if (record.missed.isNotEmpty() && record.matchingPolicyVersion != TrackMatchScore.POLICY_VERSION) {
                record = record.copy(matches = emptyList(), missed = emptyList(), nextIndex = 0, ready = false)
            }

            record = record.copy(matchingPolicyVersion = TrackMatchScore.POLICY_VERSION)

            var phase = MirrorPhase.MATCHING
            var phaseCompleted = 0
            var phaseTotal = 0

            suspend fun checkpoint() {
                active()
                store.put(record)
                onProgress(
                    PlaylistMirrorState(
                        tracks.size,
                        record.matches.size,
                        record.missed.size,
                        record.ready,
                        phase = phase,
                        phaseCompleted = phaseCompleted,
                        phaseTotal = phaseTotal,
                    ),
                )
            }

            fun updateProgress(progress: PrivatePlaylistImportProgress?) {
                progress ?: return
                phase =
                    when (progress.phase) {
                        PrivatePlaylistImportPhase.PREPARING, PrivatePlaylistImportPhase.WRITING -> MirrorPhase.WRITING
                        PrivatePlaylistImportPhase.VERIFYING -> MirrorPhase.VERIFYING
                    }
                phaseCompleted = progress.completed
                phaseTotal = progress.total
            }

            suspend fun acceptImport(
                result: PrivatePlaylistImportResult,
                mode: PrivatePlaylistImportMode,
            ) {
                active()
                record = record.copy(destination = result.ref ?: record.destination, ready = false)
                if (mode == PrivatePlaylistImportMode.REPLACE) updateProgress(result.progress)
                checkpoint()
            }

            suspend fun import(
                request: PrivatePlaylistImportRequest,
                initial: PrivatePlaylistImportResult? = null,
            ) {
                var cursor = initial?.next
                val seen = mutableSetOf<String>()
                cursor?.let(seen::add)
                if (request.mode == PrivatePlaylistImportMode.REPLACE) {
                    record = record.copy(ready = false)
                    phase = MirrorPhase.WRITING
                    phaseCompleted = 0
                    phaseTotal = record.matches.size
                    checkpoint()
                }
                if (cursor != null) initial?.retryAfterMs?.let { delay(it.coerceIn(100L, 5000L)) }
                do {
                    active()
                    val result =
                        retryingTransient(beforeRetry = { active() }) {
                            withContext(NonCancellable) {
                                host.call(
                                    key.targetPlugin,
                                    PluginOperations.importPrivatePlaylist,
                                    request.copy(target = record.destination, cursor = cursor),
                                )
                            }
                        }
                    acceptImport(result, request.mode)
                    cursor = result.next
                    if (cursor != null && !seen.add(cursor)) throw MirrorPreparationException(MirrorFailure.INVALID_PAGINATION)
                    if (cursor != null) result.retryAfterMs?.let { delay(it.coerceIn(100L, 5000L)) }
                } while (cursor != null)
                checkNotNull(record.destination) { "The provider did not return the private playlist" }
            }
            if (record.ready) {
                val image = retryingTransient(beforeRetry = { active() }) { artworkLoader?.fetch(source, sourceArtwork) }
                import(
                    PrivatePlaylistImportRequest(
                        key.sourceKey,
                        title,
                        record.matches.map { it.destinationTrack.ref },
                        expectedAccountKey = key.targetAccount,
                        artwork = image,
                    ),
                )
                record =
                    record.copy(
                        ready = true,
                        verifiedAtMs = System.currentTimeMillis(),
                        verifiedPackages = packages,
                        matchingPolicyVersion = TrackMatchScore.POLICY_VERSION,
                    )
                checkpoint()
                return record
            }
            checkpoint()
            val image = retryingTransient(beforeRetry = { active() }) { artworkLoader?.fetch(source, sourceArtwork) }
            active()
            val ensureRequest =
                PrivatePlaylistImportRequest(
                    key.sourceKey,
                    title,
                    emptyList(),
                    expectedAccountKey = key.targetAccount,
                    mode = PrivatePlaylistImportMode.ENSURE,
                    artwork = image,
                )
            val batched =
                target.manifest.roles.audio
                    ?.batchMatching == true
            var ensured: PrivatePlaylistImportResult? = null
            if (!batched || record.nextIndex >= tracks.size) import(ensureRequest)

            suspend fun matchNext() {
                active()
                val start = record.nextIndex
                val batch = tracks.subList(start, minOf(start + if (batched) MATCH_BATCH_SIZE else 1, tracks.size))
                val outcome =
                    gate.match(key.id, background) {
                        active()
                        val callerContext = currentCoroutineContext()
                        withContext(NonCancellable) {
                            if (batched) {
                                matcher.matchBatchForIndexing(
                                    batch,
                                    key.targetPlugin,
                                    ensureRequest.copy(target = record.destination).takeIf { ensured == null },
                                    primaryStrategy = target.audioMatchStrategy(),
                                    ensureCallerActive = {
                                        callerContext.ensureActive()
                                        validate()
                                    },
                                )
                            } else {
                                AudioBatchIndexingResult(
                                    listOf(
                                        matcher.matchForIndexing(batch.single(), key.targetPlugin, strategy = target.audioMatchStrategy()),
                                    ),
                                )
                            }
                        }
                    }
                active()
                outcome.playlist?.let {
                    ensured = it
                    acceptImport(it, PrivatePlaylistImportMode.ENSURE)
                }
                outcome.playlistError?.let { throw PluginCallException(key.targetPlugin, it) }
                if (batched) checkNotNull(ensured) { "The provider did not return playlist creation state" }
                check(outcome.matches.size == batch.size) { "The provider returned an incomplete match batch" }
                batch.forEachIndexed { index, track ->
                    outcome.errors.getOrNull(index)?.let { throw PluginCallException(key.targetPlugin, it) }
                    val position = start + index
                    val candidate = outcome.matches[index]
                    record =
                        if (candidate == null) {
                            record.copy(missed = record.missed + track, nextIndex = position + 1)
                        } else {
                            record.copy(matches = record.matches + MirrorMatch(position, track, candidate), nextIndex = position + 1)
                        }
                    checkpoint()
                }
            }
            while (record.nextIndex < tracks.size) {
                retryingTransient(beforeRetry = { active() }) { matchNext() }
                yield()
            }
            if (ensured?.next != null) import(ensureRequest, ensured)
            checkNotNull(record.destination) { "The provider did not return the private playlist" }
            if (tracks.isNotEmpty() && record.matches.isEmpty()) throw MirrorPreparationException(MirrorFailure.NO_MATCHES)
            // A revisionless source is read again too: its ordered contents are the revision.
            val (confirmed, confirmedRevision) = read()
            if (confirmedRevision != revision || confirmed != tracks) throw MirrorPreparationException(MirrorFailure.SOURCE_CHANGED)
            active()
            import(
                PrivatePlaylistImportRequest(
                    key.sourceKey,
                    title,
                    record.matches.map { it.destinationTrack.ref },
                    expectedAccountKey = key.targetAccount,
                ),
            )
            record =
                record.copy(
                    ready = true,
                    verifiedAtMs = System.currentTimeMillis(),
                    verifiedPackages = packages,
                    matchingPolicyVersion = TrackMatchScore.POLICY_VERSION,
                )
            checkpoint()
            return record
        }

        private companion object {
            const val MATCH_BATCH_SIZE = 16
        }

        private fun samePackage(
            original: InstalledPlugin,
            current: InstalledPlugin?,
        ): Boolean =
            current != null && current.manifest.versionCode == original.manifest.versionCode &&
                current.signerFingerprint == original.signerFingerprint && current.installedAtMs == original.installedAtMs
    }

enum class MirrorFailure { UNSUPPORTED, ACCOUNT_CHANGED, PLUGIN_CHANGED, SOURCE_CHANGED, INVALID_PAGINATION, NO_MATCHES }

class MirrorPreparationException(
    val reason: MirrorFailure,
) : IllegalStateException(reason.name)
