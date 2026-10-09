package io.github.aedev.flow.player.renderer

import android.content.Context
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.metadata.MetadataOutput
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import io.github.aedev.flow.player.MusicVideoJoinGate
import io.github.aedev.flow.player.audio.MusicAudioTrackProbe

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicRenderersFactory(
    context: Context,
    private val audioOutputProbe: MusicAudioTrackProbe,
    private val processors: Array<AudioProcessor>,
    private val videoJoin: MusicVideoJoinGate,
) : DefaultRenderersFactory(context) {
    @Suppress("DEPRECATION")
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink =
        DefaultAudioSink
            .Builder(context)
            .setAudioTrackProvider(audioOutputProbe)
            .setAudioProcessors(processors)
            .build()

    override fun createRenderers(
        eventHandler: Handler,
        videoRendererEventListener: VideoRendererEventListener,
        audioRendererEventListener: AudioRendererEventListener,
        textRendererOutput: TextOutput,
        metadataRendererOutput: MetadataOutput,
    ): Array<Renderer> =
        super
            .createRenderers(
                eventHandler,
                videoRendererEventListener,
                audioRendererEventListener,
                textRendererOutput,
                metadataRendererOutput,
            ).map { if (it.trackType == C.TRACK_TYPE_VIDEO) InitialVideoJoinRenderer(it, videoJoin) else it }
            .toTypedArray()
}

/** Everything except finite initial-join readiness remains the shipped renderer's responsibility. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class InitialVideoJoinRenderer(
    renderer: Renderer,
    private val videoJoin: MusicVideoJoinGate,
) : ForwardingRenderer(renderer) {
    override fun isReady(): Boolean = super.isReady() || videoJoin.waiting
}
