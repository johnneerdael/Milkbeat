package io.github.aedev.flow.plugin.playback

import android.os.SystemClock
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import nl.neerdael.milkbeat.catalog.TrackDescriptor

internal class PlaybackAttemptRouting(
    val providers: List<AudioProviderAttempt>,
    val attempts: List<AudioProviderAttempt>,
    val recoveryFirst: AudioProviderAttempt?,
    val failedMatch: TrackDescriptor?,
    val excludedEclipseIds: Set<String>,
    val order: List<String>,
)

/**
 * Orders this resolve's provider attempts: the accepted provider that failed first, a stale or
 * expired match next, then the listener's configured order. An Eclipse match identifies the actual
 * add-on and recording, so it is exhausted before another add-on is tried without reordering the
 * listener's providers.
 */
internal fun playbackAttemptRouting(
    key: AudioIdentity,
    state: PluginRegistryState,
    track: TrackDescriptor,
    picture: PictureLimits?,
    preferredProviderId: String?,
    failedAcceptance: ResolvedAudio?,
    previous: ResolvedAudio?,
    protocolRecovery: Boolean,
    mediaFailures: PlaybackProviderFailures,
    eclipseMatchFailures: PlaybackProviderFailures,
    nativeContext: (String) -> Any,
): PlaybackAttemptRouting {
    val providers =
        audioProviderAttempts(state, track, withPicture = picture != null, preferredProviderId = preferredProviderId)
            .filterNot { provider ->
                val id = provider.plugin.id
                val canRematchEclipse = id == ECLIPSE_PLUGIN_ID && provider.direct == null
                !canRematchEclipse && mediaFailures.exhausted(key, id, nativeContext(id))
            }
    val failedProvider = failedAcceptance?.let { audio -> providers.firstOrNull { it.plugin.id == audio.pluginId } }
    val failedMatch = failedProvider?.takeIf { it.plugin.id == ECLIPSE_PLUGIN_ID && it.direct == null }?.let { failedAcceptance?.track }
    val excludedEclipseIds = eclipseMatchFailures.exhaustedProviders(key, nativeContext(ECLIPSE_PLUGIN_ID))
    val pinned =
        failedAcceptance
            ?.track
            ?.takeUnless { failedMatch != null && it.ref.providerId in excludedEclipseIds }
            ?.let { recording -> failedProvider?.let { AudioProviderAttempt(it.plugin, recording) } }
            ?: previous
                ?.takeIf {
                    picture != null || !it.isValidAt(System.currentTimeMillis(), SystemClock.elapsedRealtime()) ||
                        it.runtimeReceipt?.isCurrent() == false
                }?.let { audio ->
                    providers
                        .firstOrNull { provider ->
                            provider.plugin.id == audio.pluginId &&
                                (provider.plugin.id != ECLIPSE_PLUGIN_ID || audio.track.ref.providerId !in excludedEclipseIds)
                        }?.let { AudioProviderAttempt(it.plugin, audio.track) }
                }
    val recoveryFirst = pinned ?: failedProvider?.takeIf { failedMatch != null }
    val attempts =
        when {
            recoveryFirst == null -> providers
            picture != null && !protocolRecovery -> listOf(recoveryFirst)
            else -> listOf(recoveryFirst) + providers.filterNot { it.plugin.id == recoveryFirst.plugin.id }
        }
    val order = (if (picture != null) attempts else providers).map { "${it.plugin.id}:${it.plugin.manifest.versionCode}" }
    return PlaybackAttemptRouting(providers, attempts, recoveryFirst, failedMatch, excludedEclipseIds, order)
}
