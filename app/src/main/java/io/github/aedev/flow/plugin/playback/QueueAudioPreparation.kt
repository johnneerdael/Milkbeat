package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.plugin.runtime.PluginCallException
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioQuality

sealed interface QueuePreparationResult {
    data object Ready : QueuePreparationResult

    class Unmatched(
        val isCurrent: () -> Boolean = { true },
    ) : QueuePreparationResult

    data object Retryable : QueuePreparationResult
}

internal suspend fun PluginAudio.prepareQueue(
    track: TrackDescriptor,
    picture: PictureLimits?,
    quality: AudioQuality = AudioQuality.AUTO,
    preferredProviderId: String? = null,
    preparePicture: PictureLimits? = null,
): QueuePreparationResult {
    if (!needsQueueMatching(track, preferredProviderId)) return QueuePreparationResult.Ready
    val version = preparationVersion()
    return try {
        prepare(track, picture, quality, preferredProviderId, preparePicture)
        QueuePreparationResult.Ready
    } catch (_: AudioCatalogMiss) {
        QueuePreparationResult.Unmatched { version == preparationVersion() }
    } catch (_: PluginCallException) {
        QueuePreparationResult.Retryable
    }
}
