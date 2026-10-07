package io.github.aedev.flow.player.audio

internal class AudioOutputProgress {
    data class Result(
        val advanced: Boolean = false,
        val stalled: Boolean = false,
        val resumePositionMs: Long = 0,
    )

    private var generation: Long? = null
    private var headFrames = 0L
    private var lastProgressMs = 0L
    private var resumePositionMs = 0L
    private var previousPositionMs = 0L

    fun sample(
        generation: Long,
        headFrames: Long,
        nowMs: Long,
        positionMs: Long,
    ): Result {
        if (this.generation != generation) {
            this.generation = generation
            this.headFrames = headFrames
            lastProgressMs = nowMs
            resumePositionMs = positionMs
            previousPositionMs = positionMs
            return Result(advanced = headFrames > 0, resumePositionMs = resumePositionMs)
        }

        val delta = (headFrames - this.headFrames) and 0xffff_ffffL
        this.headFrames = headFrames
        val advanced = delta in 1..0x7fff_ffffL
        if (advanced) {
            lastProgressMs = nowMs
            // The current estimate may already include time after the final partial audio buffer.
            resumePositionMs = minOf(previousPositionMs, positionMs)
            previousPositionMs = positionMs
        }
        return Result(advanced, nowMs - lastProgressMs >= STALL_TIMEOUT_MS, resumePositionMs)
    }

    fun reset() {
        generation = null
    }

    private companion object {
        const val STALL_TIMEOUT_MS = 5_000L
    }
}
