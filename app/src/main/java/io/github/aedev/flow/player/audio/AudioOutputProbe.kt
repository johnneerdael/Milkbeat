package io.github.aedev.flow.player.audio

import kotlinx.coroutines.flow.StateFlow

internal data class AudioOutputSample(
    val generation: Long,
    val headFrames: Long,
)

internal interface AudioOutputProbe {
    val changes: StateFlow<Long>
    val monitorable: Boolean

    fun read(): AudioOutputSample?
}
