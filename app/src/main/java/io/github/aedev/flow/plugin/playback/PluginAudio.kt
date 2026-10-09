package io.github.aedev.flow.plugin.playback

import android.os.SystemClock
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioDelivery
import nl.neerdael.milkbeat.plugin.AudioQuality
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest
import nl.neerdael.milkbeat.plugin.ResolveAudioRequest
import nl.neerdael.milkbeat.plugin.ServerAbrFailure
import nl.neerdael.milkbeat.plugin.StreamFailure
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val EXPIRY_MARGIN_MS = 60_000L
private const val DEFAULT_LIFETIME_MS = 5 * 60 * 60_000L

/**
 * Plays tracks through the listener's audio plugins in their selected order. Each plugin uses its
 * own id when present, otherwise searches for a match; the next plugin is tried when no suitable
 * recording is found or the recording is unavailable. One resolve serves a music video's sound and picture; it is kept until shortly
 * before it expires, and dropped when playback reports it failed, so the plugin is asked for another.
 */
@Singleton
class PluginAudio
    @Inject
    constructor(
        private val host: PluginHost,
        private val registry: PluginRegistry,
        private val matcher: PluginTrackMatcher,
        private val accounts: PluginAccounts,
    ) {
        private val resolved = ConcurrentHashMap<AudioIdentity, ResolvedAudio>()
        private val playbackIds = ConcurrentHashMap<String, AudioIdentity>()
        private val cacheGeneration = AtomicLong()
        private val resolvedRevision = MutableStateFlow(0L)
        private val resolutionProgress = MusicResolutionProgress()
        val resolutionStatus get() = resolutionProgress.state

        fun selectForegroundPlayback(playbackId: String?) = resolutionProgress.select(playbackId)

        val videoCapablePlaybackIds: Flow<Set<String>>
            get() =
                combine(resolvedRevision, registry.state, accounts.accounts, accounts.playbackEpoch) { _, state, accountState, epoch ->
                    val context = Triple(state, accounts.identitiesOf(accountState), epoch)
                    playbackIds.entries
                        .filter { (_, identity) ->
                            resolved[identity]?.let { audio ->
                                (audio.preparationContext == context || ownsNativeSource(audio)) &&
                                    state
                                        .plugin(audio.pluginId)
                                        ?.manifest
                                        ?.roles
                                        ?.audio
                                        ?.musicVideo == true && audio.hasPreparedPicture
                            } == true
                        }.map { it.key }
                        .toSet()
                }.distinctUntilChanged()

        private fun streamContext(): Any = Triple(registry.state.value, accounts.playbackIdentitySnapshot(), accounts.playbackEpoch.value)

        private fun nativeContext(pluginId: String): Any =
            registry.state.value.plugin(pluginId) to accounts.providerPlaybackContext(pluginId)

        private fun ownsNativeSource(audio: ResolvedAudio): Boolean =
            audio.nativeBinding != null && audio.nativeBinding == nativeContext(audio.pluginId)

        internal fun needsQueueMatching(
            track: TrackDescriptor,
            preferredProviderId: String? = null,
        ): Boolean {
            val state = registry.state.value
            val first =
                (listOfNotNull(preferredProviderId) + state.selection.audio)
                    .mapNotNull(state::plugin)
                    .firstOrNull { it.enabled } ?: return false
            return directAudioTrack(track, first) == null
        }

        internal fun preparationVersion(): Any = streamContext() to cacheGeneration.get()

        private val resolutionLocks = ConcurrentHashMap<AudioIdentity, Mutex>()
        private val failures = ConcurrentHashMap<AudioIdentity, StreamFailure>()
        private val failureContexts = ConcurrentHashMap<AudioIdentity, Any>()
        private val failureProviders = ConcurrentHashMap<AudioIdentity, String>()
        private val mediaFailures = PlaybackProviderFailures()

        /** The stream for [track], resolving it unless a still-valid one covers what is asked. */
        suspend fun resolve(
            track: TrackDescriptor,
            picture: PictureLimits?,
            quality: AudioQuality = AudioQuality.AUTO,
            playbackId: String = track.ref.providerId,
            preferredProviderId: String? = null,
            preparePicture: PictureLimits? = null,
        ): ResolvedAudio {
            val identity = track.audioIdentity()
            if (playbackIds.put(playbackId, identity) != identity) {
                resolvedRevision.update { revision -> revision + 1 }
            }
            val ticket = resolutionProgress.begin(playbackId)
            try {
                return resolveLocked(track, picture, quality, strict = false, preferredProviderId, preparePicture, ticket)
            } finally {
                resolutionProgress.finish(ticket)
            }
        }

        suspend fun prepare(
            track: TrackDescriptor,
            picture: PictureLimits?,
            quality: AudioQuality = AudioQuality.AUTO,
            preferredProviderId: String? = null,
            preparePicture: PictureLimits? = null,
        ): ResolvedAudio = resolveLocked(track, picture, quality, strict = true, preferredProviderId, preparePicture)

        private suspend fun resolveLocked(
            track: TrackDescriptor,
            picture: PictureLimits?,
            quality: AudioQuality,
            strict: Boolean,
            preferredProviderId: String?,
            preparePicture: PictureLimits?,
            ticket: MusicResolutionProgress.Ticket? = null,
        ): ResolvedAudio {
            val key = track.audioIdentity()
            return resolutionLocks.getOrPut(key) { Mutex() }.withLock {
                resolveStream(track, picture, quality, strict, preferredProviderId, preparePicture, ticket)
            }
        }

        private suspend fun resolveStream(
            track: TrackDescriptor,
            picture: PictureLimits?,
            quality: AudioQuality,
            strict: Boolean,
            preferredProviderId: String?,
            preparePicture: PictureLimits?,
            ticket: MusicResolutionProgress.Ticket?,
        ): ResolvedAudio {
            val context = streamContext()
            val generation = cacheGeneration.get()
            val version = context to generation
            val key = track.audioIdentity()
            val failedAudio = resolved[key]
            val acceptedSabrFailure = failures[key] != null && resolved[key]?.stream?.serverAbr != null
            if ((failures[key]?.serverAbrFailure != null || acceptedSabrFailure) && failureContexts[key] != null &&
                failureContexts[key] != (failedAudio?.nativeBinding?.let { nativeContext(failedAudio.pluginId) } ?: context)
            ) {
                failures.remove(key)
                failureContexts.remove(key)
                throw IOException("The accepted playback account or provider changed")
            }
            val state = registry.state.value
            val previous = resolved[key]?.takeIf { it.preparationContext == context || ownsNativeSource(it) }
            val pendingFailure = failures[key]
            val providers =
                audioProviderAttempts(state, track, withPicture = picture != null, preferredProviderId = preferredProviderId)
                    .filterNot { mediaFailures.exhausted(key, it.plugin.id, nativeContext(it.plugin.id)) }
            val pinned =
                previous
                    ?.takeIf {
                        picture != null || !it.isValidAt(System.currentTimeMillis(), SystemClock.elapsedRealtime()) ||
                            it.runtimeReceipt?.isCurrent() == false
                    }?.let { audio ->
                        providers.firstOrNull { it.plugin.id == audio.pluginId }?.let { AudioProviderAttempt(it.plugin, audio.track) }
                    }
            val protocolRecovery = failures[key]?.serverAbrFailure != null || acceptedSabrFailure
            val attempts =
                when {
                    protocolRecovery && pinned != null -> listOf(pinned) + providers.filterNot { it.plugin.id == pinned.plugin.id }
                    pinned == null -> providers
                    picture != null -> listOf(pinned)
                    else -> listOf(pinned) + providers.filterNot { it.plugin.id == pinned.plugin.id }
                }
            val order = (if (picture != null) attempts else providers).map { "${it.plugin.id}:${it.plugin.manifest.versionCode}" }
            resolved[key]
                ?.takeIf {
                    it.coversResolution(
                        context,
                        order,
                        quality,
                        picture,
                        preparePicture,
                        state
                            .plugin(it.pluginId)
                            ?.manifest
                            ?.roles
                            ?.audio
                            ?.musicVideo == true,
                    )
                }?.let { return it }
            if (attempts.isEmpty()) {
                throw PluginCallException("none", PluginError(PluginErrorCode.UNAVAILABLE, "No audio plugin plays ${track.title}"))
            }
            var last: PluginCallException? = null
            var lastPictureUnavailable: PictureUnavailable? = null
            for ((plugin, known) in attempts) {
                val matchProgress: (TrackMatchProgress) -> Unit = { phase ->
                    resolutionProgress.update(
                        ticket,
                        plugin.manifest.name,
                        if (phase == TrackMatchProgress.SAVED_MATCH) MusicResolutionStage.SAVED_MATCH else MusicResolutionStage.MATCHING,
                    )
                }
                var legacyPicture =
                    preparePicture != null && picture == null &&
                        plugin.manifest.roles.audio
                            ?.musicVideo == true && plugin.manifest.api.target < 7
                var playable =
                    try {
                        known ?: match(track, plugin.id, strict, strategy = plugin.audioMatchStrategy(), onProgress = matchProgress)
                            ?: continue
                    } catch (
                        error: PluginCallException,
                    ) {
                        last = error
                        continue
                    }
                for (attempt in 0..1) {
                    val request =
                        ResolveAudioRequest(
                            track = playable,
                            quality = quality,
                            video = picture != null || legacyPicture,
                            maxVideoHeight = (picture ?: preparePicture)?.maxHeight,
                            videoCodecs = (picture ?: preparePicture)?.codecs.orEmpty(),
                            failure = pendingFailure?.takeIf { failureProviders[key] == plugin.id },
                            prepareVideo =
                                preparePicture != null && plugin.manifest.roles.audio
                                    ?.musicVideo == true,
                        )
                    try {
                        resolutionProgress.update(ticket, plugin.manifest.name, MusicResolutionStage.LOADING_STREAM)
                        val acceptedContext = nativeContext(plugin.id)
                        val (stream, receipt) =
                            host.withPlaybackReceipt(
                                plugin.id,
                            ) { host.call(plugin.id, PluginOperations.resolveAudio, request) }
                        rejectAudioOnlyHlsPicture(stream, picture != null)
                        if (picture != null && stream.video == null && stream.serverAbr == null && !isHlsStream(stream)) {
                            throw PluginCallException(
                                plugin.id,
                                PluginError(PluginErrorCode.UNAVAILABLE, "The audio provider has no picture for this recording"),
                            )
                        }
                        if (protocolRecovery && previous?.pluginId == plugin.id && previous.stream.serverAbr != null &&
                            stream.serverAbr != null &&
                            previous.stream.serverAbr?.videoId != stream.serverAbr?.videoId
                        ) {
                            throw IOException("The provider changed the accepted recording")
                        }
                        validateAudioStream(plugin.id, stream, playbackGrants(plugin.id))
                        requireNativeSabrMarker(stream.mimeType, stream.serverAbr)
                        validateServerAbr(
                            stream.serverAbr,
                            picture != null,
                            registry.state.value
                                .plugin(plugin.id)
                                ?.grantedNetwork
                                .orEmpty(),
                            allowPicture = request.video || request.prepareVideo,
                        )
                        val boundPresentation = stream.serverAbr != null || stream.audioFormat != null
                        if (boundPresentation &&
                            (acceptedContext != nativeContext(plugin.id) || generation != cacheGeneration.get())
                        ) {
                            throw IOException("The accepted playback account or provider changed")
                        }
                        val lifetime = stream.expiresInMs ?: DEFAULT_LIFETIME_MS
                        return ResolvedAudio(
                            plugin.id,
                            playable,
                            stream,
                            System.currentTimeMillis() + lifetime - EXPIRY_MARGIN_MS,
                            picture != null,
                            order,
                            request,
                            context,
                            if (stream.serverAbr != null) receipt else null,
                            if (boundPresentation) acceptedContext else null,
                            if (boundPresentation) SystemClock.elapsedRealtime() + lifetime - EXPIRY_MARGIN_MS else null,
                        ).also {
                            pendingFailure?.let { failure ->
                                if (failures.remove(key, failure)) {
                                    failureProviders.remove(key)
                                    failureContexts.remove(key)
                                }
                            }
                            synchronized(resolved) {
                                if (generation == cacheGeneration.get() &&
                                    (version == preparationVersion() || (stream.serverAbr != null && ownsNativeSource(it)))
                                ) {
                                    resolved[key] = it
                                    resolvedRevision.update { revision -> revision + 1 }
                                }
                            }
                        }
                    } catch (e: PictureUnavailable) {
                        lastPictureUnavailable = e
                        break
                    } catch (e: PluginCallException) {
                        last = e
                        if (legacyPicture && attempt == 0 &&
                            e.error.code in setOf(PluginErrorCode.UNAVAILABLE, PluginErrorCode.NOT_FOUND)
                        ) {
                            legacyPicture = false
                            continue
                        }
                        if (e.error.code !in setOf(PluginErrorCode.UNAVAILABLE, PluginErrorCode.NOT_FOUND)) break
                        if (known != null) break
                        matcher.invalidate(track, plugin.id)
                        if (attempt != 0) break
                        playable =
                            match(
                                track,
                                plugin.id,
                                strict,
                                excludedId = playable.ref.providerId,
                                strategy = plugin.audioMatchStrategy(),
                                onProgress = matchProgress,
                            )
                                ?: break
                    } catch (e: IOException) {
                        if (context != streamContext() || generation != cacheGeneration.get()) throw e
                        last =
                            PluginCallException(
                                plugin.id,
                                PluginError(PluginErrorCode.UNAVAILABLE, "The provider returned unusable playback metadata"),
                            )
                        break
                    }
                }
            }
            lastPictureUnavailable?.let { throw it }
            if (strict && last?.error?.code == PluginErrorCode.NOT_FOUND) {
                throw PluginCallException(
                    last.pluginId,
                    PluginError(PluginErrorCode.UNAVAILABLE, "A matched recording could not be prepared"),
                )
            }
            if (strict && last == null) throw AudioCatalogMiss()
            throw last ?: PluginCallException("none", PluginError(PluginErrorCode.NOT_FOUND, "No audio plugin found ${track.title}"))
        }

        private suspend fun match(
            track: TrackDescriptor,
            pluginId: String,
            strict: Boolean,
            excludedId: String? = null,
            strategy: nl.neerdael.milkbeat.plugin.AudioMatchStrategy,
            onProgress: (TrackMatchProgress) -> Unit = {},
        ): TrackDescriptor? =
            if (strict) {
                matcher.matchForIndexing(
                    track,
                    pluginId,
                    excludedId,
                    strategy,
                )
            } else {
                matcher.match(track, pluginId, excludedId, strategy, onProgress)
            }

        suspend fun refreshBound(audio: ResolvedAudio): ResolvedAudio {
            verifyBound(audio)
            val request = (audio.request ?: ResolveAudioRequest(audio.track, video = audio.withPicture)).copy(failure = null)
            val (stream, receipt) =
                host.withPlaybackReceipt(
                    audio.pluginId,
                ) { host.call(audio.pluginId, PluginOperations.resolveAudio, request) }
            verifyBound(audio)
            if (audio.stream.serverAbr != null && stream.serverAbr != null &&
                audio.stream.serverAbr?.videoId != stream.serverAbr?.videoId
            ) {
                throw IOException("The provider changed the accepted recording")
            }
            rejectAudioOnlyHlsPicture(stream, audio.withPicture)
            validateAudioStream(audio.pluginId, stream, playbackGrants(audio.pluginId))
            requireNativeSabrMarker(stream.mimeType, stream.serverAbr)
            validateServerAbr(
                stream.serverAbr,
                audio.withPicture,
                registry.state.value
                    .plugin(audio.pluginId)
                    ?.grantedNetwork
                    .orEmpty(),
                allowPicture = request.video || request.prepareVideo,
            )
            val previousHls =
                audio.stream.mimeType
                    .substringBefore(';')
                    .lowercase() in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")
            val nextHls =
                stream.mimeType.substringBefore(';').lowercase() in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")
            if ((audio.stream.serverAbr != null) != (stream.serverAbr != null) || previousHls != nextHls ||
                audio.stream.drm?.scheme != stream.drm?.scheme ||
                (audio.withPicture && stream.video == null && stream.serverAbr == null && !isHlsStream(stream))
            ) {
                throw PluginCallException(
                    audio.pluginId,
                    PluginError(PluginErrorCode.UNAVAILABLE, "The provider changed this recording's delivery format"),
                )
            }
            return ResolvedAudio(
                audio.pluginId,
                audio.track,
                stream,
                System.currentTimeMillis() + (stream.expiresInMs ?: DEFAULT_LIFETIME_MS) - EXPIRY_MARGIN_MS,
                audio.withPicture,
                audio.providerOrder,
                request,
                audio.preparationContext,
                if (stream.serverAbr != null) receipt else null,
                audio.nativeBinding,
                if (stream.serverAbr != null || stream.audioFormat != null) {
                    SystemClock.elapsedRealtime() + (stream.expiresInMs ?: DEFAULT_LIFETIME_MS) - EXPIRY_MARGIN_MS
                } else {
                    null
                },
            )
        }

        internal fun verifyBound(audio: ResolvedAudio) {
            if (audio.runtimeReceipt?.isCurrent() == false) throw PluginPlaybackSessionLost()
            if (audio.nativeBinding?.let { it != nativeContext(audio.pluginId) }
                ?: (audio.preparationContext != null && audio.preparationContext != streamContext())
            ) {
                throw IOException("The accepted playback account or provider changed")
            }
        }

        internal suspend fun acquirePlaybackLease(audio: ResolvedAudio): PluginPlaybackLease {
            verifyBound(audio)
            val held = host.playbackLease(audio.pluginId)
            try {
                audio.runtimeReceipt?.let(held::verify)
                verifyBound(audio)
                return held
            } catch (error: Throwable) {
                held.close()
                throw error
            }
        }

        internal fun playbackGrants(pluginId: String): List<String> =
            registry.state.value
                .plugin(pluginId)
                ?.takeIf { it.enabled }
                ?.grantedNetwork
                .orEmpty()

        /** How the first audio plugin that would play [track] delivers its streams. */
        fun deliveryFor(
            track: TrackDescriptor,
            preferredProviderId: String? = null,
        ): AudioDelivery =
            audioProviderAttempts(registry.state.value, track, preferredProviderId = preferredProviderId)
                .firstOrNull()
                ?.plugin
                ?.manifest
                ?.roles
                ?.audio
                ?.delivery ?: AudioDelivery.PROGRESSIVE

        fun knownAliases(track: TrackDescriptor): Set<String> {
            val native = resolved[track.audioIdentity()]?.track ?: return emptySet()
            return setOf(native.ref.providerId) + native.ids.filterKeys { it != "isrc" }.values
        }

        /** The current-context stream for [id], excluding a URL invalidated during recovery. */
        fun current(id: String): ResolvedAudio? =
            playbackIds[id]?.let(resolved::get)?.takeIf {
                it.validUntilMs != 0L && (it.preparationContext == streamContext() || ownsNativeSource(it))
            }

        /** Playback of [id] failed on [url] with [status]; the next resolve asks the plugin for another. */
        fun failed(
            id: String,
            url: String,
            status: Int?,
            reloadPlaybackContext: String? = null,
            serverAbrFailure: ServerAbrFailure? = null,
        ) {
            val key = playbackIds[id] ?: return
            resolved[key]?.let { audio ->
                failureProviders[key] = audio.pluginId
                if (serverAbrFailure !in setOf(ServerAbrFailure.URL_EXPIRED, ServerAbrFailure.PLAYBACK_CONTEXT_RELOAD)) {
                    mediaFailures.record(key, audio.pluginId, nativeContext(audio.pluginId))
                }
                (audio.nativeBinding ?: audio.preparationContext)?.let { failureContexts[key] = it }
            }
            failures[key] = StreamFailure(url, status, reloadPlaybackContext, serverAbrFailure)
            if (status == 403 || serverAbrFailure != null || resolved[key]?.stream?.serverAbr != null) {
                forget(id)
            } else {
                resolved.remove(key)
                resolvedRevision.update { it + 1 }
            }
        }

        /** [pluginId]'s own track for [track]: the track itself when the plugin plays its ids, else its match. */
        suspend fun playableIn(
            track: TrackDescriptor,
            pluginId: String,
        ): TrackDescriptor? {
            val plugin = registry.state.value.plugin(pluginId) ?: return null
            directAudioTrack(track, plugin)?.let { return it }
            if (plugin.manifest.roles.audio
                    ?.match != true
            ) {
                return null
            }
            return resolved[track.audioIdentity()]?.takeIf { it.pluginId == pluginId }?.track
                ?: matcher.match(track, pluginId, strategy = plugin.audioMatchStrategy())
        }

        /** Expire URLs for [id], retaining the accepted recording for same-context recovery. */
        fun forget(id: String) {
            synchronized(resolved) {
                cacheGeneration.incrementAndGet()
                playbackIds[id]?.let { key ->
                    resolved.computeIfPresent(key) { _, audio ->
                        ResolvedAudio(
                            audio.pluginId,
                            audio.track,
                            audio.stream,
                            0L,
                            audio.withPicture,
                            audio.providerOrder,
                            audio.request,
                            audio.preparationContext,
                            audio.runtimeReceipt,
                            audio.nativeBinding,
                            audio.nativeValidUntilElapsedMs?.let { 0L },
                        )
                    }
                }
            }
            resolvedRevision.update { it + 1 }
        }

        fun forgetAll() {
            synchronized(resolved) {
                cacheGeneration.incrementAndGet()
                resolved.clear()
                failures.clear()
                failureContexts.clear()
                failureProviders.clear()
                mediaFailures.clear()
            }
            resolvedRevision.update { it + 1 }
        }

        /** Reports a listen to the plugin that played it, when it reports listens and the listener allows it. */
        suspend fun reportListen(
            track: TrackDescriptor,
            playedMs: Long,
            durationMs: Long?,
        ) {
            val played = resolved[track.audioIdentity()] ?: return
            val plugin = registry.state.value.plugin(played.pluginId) ?: return
            if (plugin.manifest.roles.audio
                    ?.reportPlayback != true
            ) {
                return
            }
            host.call(
                played.pluginId,
                PluginOperations.reportListen,
                ReportPlaybackRequest(played.track.ref, played.stream.trackingToken, playedMs, durationMs),
            )
        }
    }
