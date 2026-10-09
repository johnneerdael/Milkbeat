package io.github.aedev.flow.player.audio

import android.content.Context
import android.media.AudioTrack
import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.player.diagnostics.TraceEvent
import io.github.aedev.flow.player.diagnostics.TraceField
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

// The replacement output-provider API exposes an estimated position, but not its AudioTrack.
// Retain the factory hook to observe actual frames while Media3 still constructs and owns the track.
@Suppress("DEPRECATION")
@OptIn(UnstableApi::class)
internal class MusicAudioTrackProbe(
    private val provider: DefaultAudioSink.AudioTrackProvider = DefaultAudioSink.AudioTrackProvider.DEFAULT,
) : AudioOutputProbe,
    DefaultAudioSink.AudioTrackProvider by provider {
    private data class Output(
        val generation: Long,
        val track: AudioTrack,
        val monitorable: Boolean,
    )

    private val generation = AtomicLong()
    private val output = AtomicReference<Output?>()
    override val changes = MutableStateFlow(0L)
    override val monitorable: Boolean get() = output.get()?.monitorable != false

    override fun getAudioTrack(
        audioTrackConfig: AudioSink.AudioTrackConfig,
        audioAttributes: AudioAttributes,
        audioSessionId: Int,
        context: Context?,
    ): AudioTrack {
        val track = provider.getAudioTrack(audioTrackConfig, audioAttributes, audioSessionId, context)
        val id = generation.incrementAndGet()
        val offloaded = Build.VERSION.SDK_INT >= 29 && track.isOffloadedPlayback
        output.set(Output(id, track, Util.isEncodingLinearPcm(audioTrackConfig.encoding) && !offloaded))
        changes.value = id
        PlaybackTrace.event(
            TraceEvent.AUDIO_TRACK_CREATED,
            TraceField.GENERATION to id,
            TraceField.SESSION_ID to track.audioSessionId.toLong(),
            TraceField.SAMPLE_RATE to audioTrackConfig.sampleRate.toLong(),
            TraceField.ENCODING to audioTrackConfig.encoding.toLong(),
            TraceField.OFFLOAD to if (offloaded) 1L else 0L,
        )
        return track
    }

    override fun read(): AudioOutputSample? {
        val current = output.get() ?: return null
        val head =
            try {
                if (current.track.state == AudioTrack.STATE_INITIALIZED) current.track.playbackHeadPosition.toLong() and 0xffff_ffffL else 0
            } catch (_: IllegalStateException) {
                0
            }
        return AudioOutputSample(current.generation, head)
    }
}
