package io.github.aedev.flow.plugin.preload

import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.playback.PluginTrackMatcher
import io.github.aedev.flow.plugin.playback.audioMatchStrategy
import io.github.aedev.flow.plugin.playback.audioProviderAttempts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.ownTracksOnlyAudio
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.plugin.runtime.retryingTransient
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.LibraryRequest
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.catalog.TracksRequest
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import javax.inject.Inject

class PlaylistPreloadRunner
    @Inject
    constructor(
        private val host: PluginHost,
        private val registry: PluginRegistry,
        private val accounts: PluginAccounts,
        private val matcher: PluginTrackMatcher,
    ) {
        suspend fun run(
            metadataId: String,
            accountKey: String,
            audioIds: List<String>,
            onProgress: suspend (PlaylistPreloadProgress) -> Unit,
        ): PlaylistPreloadProgress {
            currentCoroutineContext().ensureActive()
            if (accounts.accounts.value[metadataId] == null) {
                withContext(NonCancellable) { accounts.refresh(metadataId) }
                currentCoroutineContext().ensureActive()
            }
            val initial = registry.state.value
            val source = initial.plugin(metadataId) ?: throw PlaylistPreloadException(PlaylistPreloadFailure.PLUGIN_CHANGED)
            val surfaces =
                source.manifest.roles.metadata
                    ?.surfaces
                    .orEmpty()
            if (MetadataSurface.LIBRARY !in surfaces || MetadataSurface.TRACKS !in surfaces) {
                throw PlaylistPreloadException(PlaylistPreloadFailure.UNSUPPORTED_LIBRARY)
            }
            val ownTracksIds = ownTracksOnlyAudio(initial.plugins, initial.selection).map { it.id }
            if (ownTracksIds.isEmpty() &&
                audioIds.none {
                    initial
                        .plugin(it)
                        ?.manifest
                        ?.roles
                        ?.audio != null
                }
            ) {
                throw PlaylistPreloadException(PlaylistPreloadFailure.NO_AUDIO_PROVIDER)
            }
            val versions = (audioIds + ownTracksIds + metadataId).distinct().associateWith { initial.plugin(it)?.manifest?.versionCode }
            var progress = PlaylistPreloadProgress()

            suspend fun validate() {
                currentCoroutineContext().ensureActive()
                if ((accounts.accounts.value[metadataId] as? ProviderAccount.SignedIn)?.key != accountKey) {
                    throw PlaylistPreloadException(PlaylistPreloadFailure.ACCOUNT_CHANGED)
                }
                val current = registry.state.value
                if (current.selection.audio != audioIds ||
                    ownTracksOnlyAudio(current.plugins, current.selection).map { it.id } != ownTracksIds
                ) {
                    throw PlaylistPreloadException(PlaylistPreloadFailure.PROVIDERS_CHANGED)
                }
                if (versions.any { (id, version) -> current.plugin(id)?.manifest?.versionCode != version }) {
                    throw PlaylistPreloadException(PlaylistPreloadFailure.PLUGIN_CHANGED)
                }
            }

            suspend fun emit() {
                validate()
                onProgress(progress)
            }

            val collections = linkedSetOf<EntityRef>()
            val libraryCursors = mutableSetOf<String>()
            var libraryCursor: String? = null
            emit()
            try {
                do {
                    validate()
                    val page =
                        retryingTransient(beforeRetry = { validate() }) {
                            withContext(NonCancellable) {
                                host.call(metadataId, PluginOperations.library, LibraryRequest(cursor = libraryCursor))
                            }
                        }
                    validate()
                    page.blocks.filterIsInstance<CollectionBlock>().flatMap { it.items }.forEach { item ->
                        if (item.entity.kind == EntityKind.PLAYLIST) collections += item.entity
                    }
                    progress = progress.copy(collections = collections.size)
                    emit()
                    libraryCursor = page.nextCursor
                    if (libraryCursor != null && !libraryCursors.add(libraryCursor)) {
                        throw PlaylistPreloadException(PlaylistPreloadFailure.INVALID_PAGINATION)
                    }
                } while (libraryCursor != null)

                val seenTracks = mutableSetOf<String>()
                for (collection in collections) {
                    val trackCursors = mutableSetOf<String>()
                    var trackCursor: String? = null
                    do {
                        validate()
                        val page =
                            retryingTransient(beforeRetry = { validate() }) {
                                withContext(NonCancellable) {
                                    host.call(metadataId, PluginOperations.tracks, TracksRequest(collection, trackCursor))
                                }
                            }
                        validate()
                        for (track in page.tracks) {
                            validate()
                            if (!seenTracks.add(PluginTrackMatcher.fingerprint(track))) continue
                            val matched = index(track) { validate() }
                            progress =
                                progress.copy(
                                    indexed = progress.indexed + 1,
                                    matched = progress.matched + if (matched) 1 else 0,
                                    unavailable = progress.unavailable + if (matched) 0 else 1,
                                )
                            emit()
                        }
                        trackCursor = page.next
                        if (trackCursor != null && !trackCursors.add(trackCursor)) {
                            throw PlaylistPreloadException(PlaylistPreloadFailure.INVALID_PAGINATION)
                        }
                    } while (trackCursor != null)
                    progress = progress.copy(scannedCollections = progress.scannedCollections + 1)
                    emit()
                }
            } catch (e: PluginCallException) {
                currentCoroutineContext().ensureActive()
                if (e.pluginId == metadataId && e.error.code == PluginErrorCode.SIGN_IN_EXPIRED) {
                    if ((accounts.accounts.value[metadataId] as? ProviderAccount.SignedIn)?.key != accountKey) {
                        throw PlaylistPreloadException(PlaylistPreloadFailure.ACCOUNT_CHANGED)
                    }
                    accounts.expired(metadataId)
                }
                throw e
            }
            progress = progress.copy(finished = true)
            emit()
            return progress
        }

        private suspend fun index(
            track: TrackDescriptor,
            validate: suspend () -> Unit,
        ): Boolean {
            var unavailableProvider: PluginCallException? = null
            for (attempt in audioProviderAttempts(registry.state.value, track)) {
                validate()
                if (attempt.direct != null) return true
                val candidate =
                    try {
                        // Interrupting QuickJS can leave a rejected host promise for its next evaluation.
                        // Finish this bounded request; validation then stops a cancelled indexing job.
                        retryingTransient(beforeRetry = validate) {
                            withContext(NonCancellable) {
                                matcher.matchForIndexing(track, attempt.plugin.id, strategy = attempt.plugin.audioMatchStrategy())
                            }
                        }
                    } catch (e: PluginCallException) {
                        validate()
                        if (e.error.code !in
                            setOf(
                                PluginErrorCode.UNAVAILABLE,
                                PluginErrorCode.NOT_FOUND,
                                PluginErrorCode.SIGN_IN_REQUIRED,
                                PluginErrorCode.SIGN_IN_EXPIRED,
                                PluginErrorCode.UNSUPPORTED,
                            )
                        ) {
                            throw e
                        }
                        unavailableProvider = e
                        null
                    }
                validate()
                if (candidate != null) return true
            }
            unavailableProvider?.let { throw it }
            return false
        }
    }
