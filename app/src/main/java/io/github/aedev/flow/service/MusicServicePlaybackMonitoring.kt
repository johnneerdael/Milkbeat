package io.github.aedev.flow.service

import androidx.lifecycle.lifecycleScope
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.github.aedev.flow.player.audio.MusicAudioStartGate
import io.github.aedev.flow.player.audio.visualizer.VisualizerClockListener
import io.github.aedev.flow.player.audio.visualizer.followPlayerClock
import kotlinx.coroutines.launch

/**
 * Binds the music player's monitoring listeners: the audio-start gate, account history, the
 * visualizer's player clock, and the error bridge that names the window that actually failed.
 */
internal fun Media3MusicService.installPlaybackMonitoring() {
    audioStartGate =
        MusicAudioStartGate(
            player,
            lifecycleScope,
            ::failOverSilentStart,
            onHoldChanged = {
                if (outputRecoveryReady) outputRecovery.reportedPlayer.refreshAudioHold()
            },
        ).also {
            player.addAnalyticsListener(it)
            player.addListener(it)
        }
    accountHistoryListener =
        MusicAccountHistoryListener(
            sessionPlayer,
            lifecycleScope,
            ::resolveLearnTrack,
            accountPlayHistory::reportProgress,
            lifecycleReporter = ::reportsListenLifecycle,
            confirmedAudio = { mediaId -> audioStartGate?.confirmed(mediaId) == true },
        )
    sessionPlayer.addListener(VisualizerClockListener(visualizerTap))
    lifecycleScope.launch { followPlayerClock(visualizerTap, sessionPlayer) }
    // The item that failed is not always the current one: a next-item preload can fail while
    // the previous track still plays. Only EventTime names the window that actually broke.
    player.addAnalyticsListener(
        object : AnalyticsListener {
            override fun onPlayerError(
                eventTime: AnalyticsListener.EventTime,
                error: PlaybackException,
            ) {
                handlePlayerError(error, eventTime.windowIndex)
            }
        },
    )
}

/** Whether this item's provider takes API 12 listen-lifecycle reports instead of cadence samples. */
internal fun Media3MusicService.reportsListenLifecycle(mediaId: String): Boolean {
    if (!pluginAudioReady) return false
    return pluginAudio.current(mediaId)?.let(pluginAudio::reportsListenLifecycle) == true
}
