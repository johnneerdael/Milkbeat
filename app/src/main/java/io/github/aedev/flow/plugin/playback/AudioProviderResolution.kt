package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioMatchStrategy

private const val MAX_AUDIO_PROVIDER_RESOLUTIONS = 4

internal data class AudioProviderResolution(
    val audio: ResolvedAudio? = null,
    val error: PluginCallException? = null,
    val pictureUnavailable: PictureUnavailable? = null,
)

internal suspend fun resolveAudioAttempts(
    attempts: List<AudioProviderAttempt>,
    concurrent: Boolean,
    resolve: suspend (AudioProviderAttempt) -> ResolvedAudio?,
): AudioProviderResolution =
    coroutineScope {
        suspend fun attempt(provider: AudioProviderAttempt): AudioProviderResolution =
            try {
                AudioProviderResolution(audio = resolve(provider))
            } catch (error: PluginCallException) {
                AudioProviderResolution(error = error)
            } catch (error: PictureUnavailable) {
                AudioProviderResolution(pictureUnavailable = error)
            }

        if (!concurrent || attempts.size < 2) {
            var error: PluginCallException? = null
            var pictureUnavailable: PictureUnavailable? = null
            for (provider in attempts) {
                val result = attempt(provider)
                if (result.audio != null) return@coroutineScope result
                result.error?.let { error = it }
                result.pictureUnavailable?.let { pictureUnavailable = it }
            }
            return@coroutineScope AudioProviderResolution(error = error, pictureUnavailable = pictureUnavailable)
        }
        val results = Channel<Pair<Int, AudioProviderResolution>>(Channel.UNLIMITED)
        val slots = Semaphore(MAX_AUDIO_PROVIDER_RESOLUTIONS)
        val workers =
            attempts.mapIndexed { index, provider ->
                launch {
                    slots.withPermit { results.send(index to attempt(provider)) }
                }
            }
        try {
            val completed = arrayOfNulls<AudioProviderResolution>(attempts.size)
            var decided = 0
            while (decided < attempts.size) {
                val (index, result) = results.receive()
                completed[index] = result
                // A lower-ranked success waits until every higher-ranked provider has failed.
                while (decided < attempts.size) {
                    val next = completed[decided] ?: break
                    if (next.audio != null) return@coroutineScope next
                    decided++
                }
            }
            AudioProviderResolution(
                error = completed.reversed().firstNotNullOfOrNull { it?.error },
                pictureUnavailable = completed.reversed().firstNotNullOfOrNull { it?.pictureUnavailable },
            )
        } finally {
            // Left open: a cancelled loser may already be past its last suspension point and still
            // send. Nothing reads this local channel after the winner, so the late result is dropped.
            workers.forEach { it.cancel() }
        }
    }

internal suspend fun PluginTrackMatcher.playbackMatch(
    track: TrackDescriptor,
    pluginId: String,
    strict: Boolean,
    excludedId: String? = null,
    strategy: AudioMatchStrategy,
    onProgress: (TrackMatchProgress) -> Unit = {},
): TrackDescriptor? =
    if (strict) {
        matchForIndexing(track, pluginId, excludedId, strategy)
    } else {
        match(track, pluginId, excludedId, strategy, onProgress)
    }
