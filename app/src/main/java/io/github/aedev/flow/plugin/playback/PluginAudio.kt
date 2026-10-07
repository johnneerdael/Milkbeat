package io.github.aedev.flow.plugin.playback

import androidx.media3.datasource.DataSource
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
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioDelivery
import nl.neerdael.milkbeat.plugin.AudioQuality
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.PluginError
import nl.neerdael.milkbeat.plugin.PluginErrorCode
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest
import nl.neerdael.milkbeat.plugin.ResolveAudioRequest
import nl.neerdael.milkbeat.plugin.StreamFailure
import okhttp3.OkHttpClient
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

private const val EXPIRY_MARGIN_MS = 60_000L
private const val DEFAULT_LIFETIME_MS = 5 * 60 * 60_000L

internal class AudioCatalogMiss : Exception()

private data class AudioIdentity(
    val ref: EntityRef,
    val ids: Map<String, String>,
)

private fun TrackDescriptor.audioIdentity() = AudioIdentity(ref, ids.toMap())

/** A stream an audio plugin handed out, with the plugin and when to ask again. */
class ResolvedAudio(
    val pluginId: String,
    /** The track as the plugin knows it: the listener's own, or the plugin's match for it. */
    val track: TrackDescriptor,
    val stream: AudioStream,
    val validUntilMs: Long,
    /** Whether the plugin was asked for the picture too. */
    val withPicture: Boolean,
    internal val providerOrder: List<String> = emptyList(),
    internal val request: ResolveAudioRequest? = null,
    internal val preparationContext: Any? = null,
)

/** What the picture of a music video is resolved against: what this TV decodes, best first. */
class PictureLimits(
    val maxHeight: Int,
    val codecs: List<String>,
)

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

        val videoCapablePlaybackIds: Flow<Set<String>>
            get() =
                combine(resolvedRevision, registry.state, accounts.accounts) { _, state, accountState ->
                    val context = state to accountState
                    playbackIds.entries
                        .filter { (_, identity) ->
                            resolved[identity]?.let { audio ->
                                audio.preparationContext == context &&
                                    state
                                        .plugin(audio.pluginId)
                                        ?.manifest
                                        ?.roles
                                        ?.audio
                                        ?.musicVideo == true
                            } == true
                        }.map { it.key }
                        .toSet()
                }.distinctUntilChanged()

        private fun streamContext(): Any = registry.state.value to accounts.accounts.value

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

        /** The stream for [track], resolving it unless a still-valid one covers what is asked. */
        suspend fun resolve(
            track: TrackDescriptor,
            picture: PictureLimits?,
            quality: AudioQuality = AudioQuality.AUTO,
            playbackId: String = track.ref.providerId,
            preferredProviderId: String? = null,
        ): ResolvedAudio {
            val identity = track.audioIdentity()
            if (playbackIds.put(playbackId, identity) != identity) {
                resolvedRevision.update { revision -> revision + 1 }
            }
            return resolveLocked(track, picture, quality, strict = false, preferredProviderId)
        }

        suspend fun prepare(
            track: TrackDescriptor,
            picture: PictureLimits?,
            quality: AudioQuality = AudioQuality.AUTO,
            preferredProviderId: String? = null,
        ): ResolvedAudio = resolveLocked(track, picture, quality, strict = true, preferredProviderId)

        private suspend fun resolveLocked(
            track: TrackDescriptor,
            picture: PictureLimits?,
            quality: AudioQuality,
            strict: Boolean,
            preferredProviderId: String?,
        ): ResolvedAudio {
            val key = track.audioIdentity()
            return resolutionLocks.getOrPut(key) { Mutex() }.withLock {
                resolveStream(track, picture, quality, strict, preferredProviderId)
            }
        }

        private suspend fun resolveStream(
            track: TrackDescriptor,
            picture: PictureLimits?,
            quality: AudioQuality,
            strict: Boolean,
            preferredProviderId: String?,
        ): ResolvedAudio {
            val context = streamContext()
            val version = context to cacheGeneration.get()
            val key = track.audioIdentity()
            val state = registry.state.value
            val previous = resolved[key]?.takeIf { it.preparationContext == context }
            val providers = audioProviderAttempts(state, track, withPicture = picture != null, preferredProviderId = preferredProviderId)
            val pinned =
                previous?.takeIf { picture != null || it.validUntilMs <= System.currentTimeMillis() }?.let { audio ->
                    providers.firstOrNull { it.plugin.id == audio.pluginId }?.let { AudioProviderAttempt(it.plugin, audio.track) }
                }
            val attempts =
                when {
                    pinned == null -> providers
                    picture != null -> listOf(pinned)
                    else -> listOf(pinned) + providers.filterNot { it.plugin.id == pinned.plugin.id }
                }
            val order = (if (picture != null) attempts else providers).map { "${it.plugin.id}:${it.plugin.manifest.versionCode}" }
            resolved[key]
                ?.takeIf {
                    it.preparationContext == context && it.providerOrder == order && it.validUntilMs > System.currentTimeMillis() &&
                        it.request?.quality == quality && (
                            picture == null || (
                                it.withPicture &&
                                    it.request.maxVideoHeight == picture.maxHeight && it.request.videoCodecs == picture.codecs
                            )
                        )
                }?.let { return it }
            if (attempts.isEmpty()) {
                throw PluginCallException("none", PluginError(PluginErrorCode.UNAVAILABLE, "No audio plugin plays ${track.title}"))
            }
            var last: PluginCallException? = null
            for ((plugin, known) in attempts) {
                var playable = known ?: match(track, plugin.id, strict, strategy = plugin.audioMatchStrategy()) ?: continue
                for (attempt in 0..1) {
                    val request =
                        ResolveAudioRequest(
                            track = playable,
                            quality = quality,
                            video = picture != null,
                            maxVideoHeight = picture?.maxHeight,
                            videoCodecs = picture?.codecs.orEmpty(),
                            failure = failures.remove(key),
                        )
                    try {
                        val stream = host.call(plugin.id, PluginOperations.resolveAudio, request)
                        if (picture != null && stream.video == null) {
                            throw PluginCallException(
                                plugin.id,
                                PluginError(PluginErrorCode.UNAVAILABLE, "The audio provider has no picture for this recording"),
                            )
                        }
                        validateDrm(plugin.id, stream)
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
                        ).also {
                            synchronized(resolved) {
                                if (version == preparationVersion()) {
                                    resolved[key] = it
                                    resolvedRevision.update { revision -> revision + 1 }
                                }
                            }
                        }
                    } catch (e: PluginCallException) {
                        last = e
                        if (e.error.code != PluginErrorCode.UNAVAILABLE && e.error.code != PluginErrorCode.NOT_FOUND) throw e
                        if (known != null) break
                        matcher.invalidate(track, plugin.id)
                        if (attempt != 0) break
                        playable =
                            match(track, plugin.id, strict, excludedId = playable.ref.providerId, strategy = plugin.audioMatchStrategy())
                                ?: break
                    }
                }
            }
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
        ): TrackDescriptor? =
            if (strict) {
                matcher.matchForIndexing(
                    track,
                    pluginId,
                    excludedId,
                    strategy,
                )
            } else {
                matcher.match(track, pluginId, excludedId, strategy)
            }

        suspend fun refreshBound(audio: ResolvedAudio): ResolvedAudio {
            val request = audio.request ?: ResolveAudioRequest(audio.track, video = audio.withPicture)
            val stream = host.call(audio.pluginId, PluginOperations.resolveAudio, request)
            validateDrm(audio.pluginId, stream)
            val previousHls =
                audio.stream.mimeType
                    .substringBefore(';')
                    .lowercase() in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")
            val nextHls =
                stream.mimeType.substringBefore(';').lowercase() in setOf("application/x-mpegurl", "application/vnd.apple.mpegurl")
            if (previousHls != nextHls || audio.stream.drm?.scheme != stream.drm?.scheme || (audio.withPicture && stream.video == null)) {
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
            )
        }

        private fun validateDrm(
            pluginId: String,
            stream: AudioStream,
        ) {
            val drm = stream.drm ?: return
            try {
                checkedPluginDrmUrl(
                    drm.licenseUrl,
                    registry.state.value
                        .plugin(pluginId)
                        ?.grantedNetwork
                        .orEmpty(),
                )
            } catch (error: IOException) {
                throw PluginCallException(
                    pluginId,
                    PluginError(
                        PluginErrorCode.UNSUPPORTED,
                        error.message ?: "Invalid plugin DRM destination",
                    ),
                )
            }
        }

        /** A dedicated license transport; media caches and media headers never carry license data. */
        internal fun drmDataSourceFactory(
            binding: BoundPluginAudio,
            base: OkHttpClient,
        ): DataSource.Factory =
            pluginDrmDataSourceFactory(base, binding) {
                registry.state.value
                    .plugin(binding.initial.pluginId)
                    ?.grantedNetwork
                    .orEmpty()
            }

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
                it.validUntilMs != 0L && it.preparationContext == streamContext()
            }

        /** Playback of [id] failed on [url] with [status]; the next resolve asks the plugin for another. */
        fun failed(
            id: String,
            url: String,
            status: Int?,
        ) {
            val key = playbackIds[id] ?: return
            failures[key] = StreamFailure(url, status)
            if (status == 403) {
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
