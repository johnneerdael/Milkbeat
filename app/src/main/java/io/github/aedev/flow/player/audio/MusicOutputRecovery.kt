package io.github.aedev.flow.player.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.player.error.PlayerDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
internal class MusicOutputRecovery(
    private val player: Player,
    private val scope: CoroutineScope,
    private val output: AudioOutputProbe,
    private val clockMs: () -> Long,
    private val onWarning: (Boolean) -> Unit = {},
) : AutoCloseable {
    val reportedPlayer = OutputReportingPlayer(player, ::onPlayRequest, ::onSeekRequest, { cancel(clearHold = true) })
    var isRecovering = false
        private set
    val hasOutputFailure: Boolean get() = heldPositionMs != null

    private val window = Timeline.Window()
    private var windowUid = currentWindowUid()
    private val progress = AudioOutputProgress()
    private val playing = MutableStateFlow(player.isPlaying)
    private var heldPositionMs: Long? = null
    private var attemptJob: Job? = null
    private var attempts = 0
    private var healthySinceMs: Long? = null
    private var changingEngine = false
    private var closed = false

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) {
                val uid = currentWindowUid()
                if (uid != windowUid || player.playerError != null) {
                    cancel(clearHold = true)
                    windowUid = uid
                } else if (isRecovering && !changingEngine &&
                    (!player.playWhenReady || player.playbackSuppressionReason != Player.PLAYBACK_SUPPRESSION_REASON_NONE)
                ) {
                    cancel(clearHold = false)
                    changeEngine { player.pause() }
                }
                playing.value = player.isPlaying
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (!changingEngine && reason == Player.DISCONTINUITY_REASON_SEEK) cancel(clearHold = true)
            }
        }

    private val monitorJob: Job

    init {
        player.addListener(listener)
        monitorJob =
            scope.launch {
                combine(playing, output.changes) { active, generation -> active to generation }
                    .distinctUntilChanged()
                    .collectLatest { (active, generation) ->
                        if (!active || (!output.monitorable && !isRecovering)) return@collectLatest
                        if (generation == 0L && !player.currentTracks.isTypeSelected(C.TRACK_TYPE_AUDIO)) return@collectLatest
                        progress.reset()
                        while (player.isPlaying && (output.monitorable || isRecovering)) {
                            val sample = output.read() ?: AudioOutputSample(output.changes.value, 0)
                            val position = if (isRecovering) heldPositionMs ?: player.currentPosition else player.currentPosition
                            val result = progress.sample(sample.generation, sample.headFrames, clockMs(), position)
                            if (result.advanced) {
                                if (isRecovering) {
                                    isRecovering = false
                                    heldPositionMs = null
                                    attemptJob?.cancel()
                                    attemptJob = null
                                    reportedPlayer.clearHold()
                                    PlayerDiagnostics.logInfo(
                                        TAG,
                                        "Audio output recovered: generation=${sample.generation} head=${sample.headFrames} attempts=$attempts",
                                    )
                                }
                                val now = clockMs()
                                if (healthySinceMs == null) healthySinceMs = now
                                if (now - healthySinceMs!! >= HEALTHY_RESET_MS) attempts = 0
                            } else {
                                healthySinceMs = null
                            }
                            if (result.stalled) {
                                PlayerDiagnostics.logWarning(
                                    TAG,
                                    "Audio output stalled: generation=${sample.generation} head=${sample.headFrames} position=${result.resumePositionMs} reported=${player.currentPosition}",
                                )
                                recover(result.resumePositionMs)
                            }
                            delay(SAMPLE_INTERVAL_MS)
                        }
                    }
            }
    }

    private fun recover(positionMs: Long) {
        attemptJob?.cancel()
        attemptJob = null
        heldPositionMs = positionMs
        healthySinceMs = null
        progress.reset()
        if (attempts >= MAX_RECOVERIES) {
            isRecovering = false
            reportedPlayer.hold(positionMs, retrying = false)
            changeEngine {
                player.pause()
                player.stop()
            }
            onWarning(false)
            return
        }

        attempts++
        isRecovering = true
        reportedPlayer.hold(positionMs, retrying = true)
        val uid = currentWindowUid()
        changeEngine {
            player.stop()
            player.seekTo(player.currentMediaItemIndex, positionMs)
        }
        onWarning(true)
        attemptJob =
            scope.launch {
                delay(RESTART_DELAY_MS)
                if (!canRestart(uid)) return@launch
                changeEngine { player.prepare() }
                delay(RECOVERY_TIMEOUT_MS)
                if (canRestart(uid)) recover(positionMs)
            }
    }

    private fun canRestart(uid: Any?): Boolean =
        !closed && isRecovering && currentWindowUid() == uid && player.playWhenReady &&
            player.playerError == null && player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE

    private fun onPlayRequest(play: Boolean) {
        if (closed) return
        if (!play) {
            cancel(clearHold = heldPositionMs == null)
        } else if (heldPositionMs != null) {
            attempts = 0
            recover(heldPositionMs!!)
        }
    }

    private fun onSeekRequest(): Boolean {
        val prepare = heldPositionMs != null
        cancel(clearHold = true)
        return prepare
    }

    private fun cancel(clearHold: Boolean) {
        attemptJob?.cancel()
        attemptJob = null
        isRecovering = false
        progress.reset()
        attempts = 0
        healthySinceMs = null
        if (clearHold) {
            heldPositionMs = null
            reportedPlayer.clearHold()
        } else {
            heldPositionMs?.let { reportedPlayer.hold(it, retrying = false) }
        }
    }

    private inline fun changeEngine(action: () -> Unit) {
        changingEngine = true
        try {
            action()
        } finally {
            changingEngine = false
        }
    }

    private fun currentWindowUid(): Any? {
        val timeline = player.currentTimeline
        return if (timeline.isEmpty) null else timeline.getWindow(player.currentMediaItemIndex, window).uid
    }

    override fun close() {
        if (closed) return
        closed = true
        attemptJob?.cancel()
        monitorJob.cancel()
        player.removeListener(listener)
    }

    private companion object {
        const val TAG = "MusicOutputRecovery"
        const val SAMPLE_INTERVAL_MS = 1_000L
        const val RESTART_DELAY_MS = 500L
        const val RECOVERY_TIMEOUT_MS = 15_000L
        const val HEALTHY_RESET_MS = 30_000L
        const val MAX_RECOVERIES = 2
    }
}
