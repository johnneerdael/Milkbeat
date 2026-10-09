package io.github.aedev.flow.plugin.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/** One source's current resolution, shared by its media and license requests. */
internal class BoundPluginAudio(
    val initial: ResolvedAudio,
    private val refresh: suspend (ResolvedAudio) -> ResolvedAudio,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val renewal = Mutex()
    private var resolved = initial

    suspend fun current(): ResolvedAudio =
        renewal.withLock {
            if (resolved.isValidAt(clock(), android.os.SystemClock.elapsedRealtime())) return@withLock resolved
            val next = refresh(resolved)
            if (initial.stream.drm != null) {
                val previous = initial.stream
                val stream = next.stream
                val previousMime =
                    previous.mimeType
                        .substringBefore(';')
                        .trim()
                        .lowercase()
                val nextMime =
                    stream.mimeType
                        .substringBefore(';')
                        .trim()
                        .lowercase()
                if (next.pluginId != initial.pluginId || next.track.ref != initial.track.ref ||
                    stream.drm?.scheme != previous.drm?.scheme || stream.renditionId != previous.renditionId ||
                    stream.cacheKey != previous.cacheKey || nextMime != previousMime || stream.codecs != previous.codecs ||
                    stream.bitrate != previous.bitrate || stream.video?.id != previous.video?.id
                ) {
                    throw IOException("The provider changed this protected recording's rendition")
                }
            }
            // The media transport keeps the first key; a later key would decrypt garbage.
            if (initial.stream.cipher != null &&
                (
                    next.pluginId != initial.pluginId || next.track.ref != initial.track.ref ||
                        next.stream.cipher != initial.stream.cipher || next.stream.renditionId != initial.stream.renditionId ||
                        next.stream.cacheKey != initial.stream.cacheKey
                )
            ) {
                throw IOException("The provider changed this encrypted recording's rendition")
            }
            next.also { resolved = it }
        }
}
