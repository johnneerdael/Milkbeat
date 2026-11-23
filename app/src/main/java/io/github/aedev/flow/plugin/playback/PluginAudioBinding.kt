package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioDelivery
import java.io.IOException

/** The accepted resolution still belongs to the current account/provider and runtime session. */
internal fun PluginAudio.verifyBound(audio: ResolvedAudio) {
    if (audio.runtimeReceipt?.isCurrent() == false) throw PluginPlaybackSessionLost()
    if (audio.nativeBinding?.let { it != nativeContext(audio.pluginId) }
        ?: (audio.preparationContext != null && audio.preparationContext != streamContext())
    ) {
        throw IOException("The accepted playback account or provider changed")
    }
}

internal suspend fun PluginAudio.acquirePlaybackLease(audio: ResolvedAudio): PluginPlaybackLease {
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

/** How the first audio plugin that would play [track] delivers its streams. */
fun PluginAudio.deliveryFor(
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

internal fun PluginAudio.needsQueueMatching(
    track: TrackDescriptor,
    preferredProviderId: String? = null,
): Boolean =
    audioProviderAttempts(registry.state.value, track, preferredProviderId = preferredProviderId)
        .firstOrNull()
        ?.let { it.direct == null } ?: false
