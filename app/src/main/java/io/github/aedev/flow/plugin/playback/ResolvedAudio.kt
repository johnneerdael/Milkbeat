package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioStream
import nl.neerdael.milkbeat.plugin.ResolveAudioRequest
import java.io.IOException

internal class AudioCatalogMiss : Exception()

/** The recording still has audio; only its requested picture delivery is unavailable. */
internal class PictureUnavailable : IOException("The accepted source has no picture")

internal fun Throwable.isPictureUnavailable(): Boolean = generateSequence(this) { it.cause }.any { it is PictureUnavailable }

internal data class AudioIdentity(
    val ref: EntityRef,
    val ids: Map<String, String>,
)

internal fun TrackDescriptor.audioIdentity() = AudioIdentity(ref, ids.toMap())

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
    internal val runtimeReceipt: PluginPlaybackReceipt? = null,
    internal val nativeBinding: Any? = null,
    internal val nativeValidUntilElapsedMs: Long? = null,
) {
    internal fun isValidAt(
        wallTimeMs: Long,
        elapsedTimeMs: Long,
    ): Boolean = nativeValidUntilElapsedMs?.let { it > elapsedTimeMs } ?: (validUntilMs > wallTimeMs)
}

/** What the picture of a music video is resolved against: what this TV decodes, best first. */
class PictureLimits(
    val maxHeight: Int,
    val codecs: List<String>,
)

/** Offer a view only after the accepted source actually supplies its picture metadata. */
internal val ResolvedAudio.hasPreparedPicture: Boolean
    get() =
        stream.video != null ||
            stream.serverAbr?.formats?.any { it.format.type == nl.neerdael.milkbeat.plugin.FormatType.VIDEO } == true ||
            (isHlsStream(stream) && !stream.requireAudioOnlyHls && (withPicture || request?.prepareVideo == true))
