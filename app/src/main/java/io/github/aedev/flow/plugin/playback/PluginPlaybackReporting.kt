package io.github.aedev.flow.plugin.playback

import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.player.diagnostics.TraceCategory
import io.github.aedev.flow.player.diagnostics.TraceEvent
import io.github.aedev.flow.player.diagnostics.TraceField
import io.github.aedev.flow.plugin.PluginHost
import io.github.aedev.flow.plugin.registry.PluginRegistry
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest

internal suspend fun reportAcceptedListen(
    host: PluginHost,
    registry: PluginRegistry,
    played: ResolvedAudio,
    playedMs: Long,
    durationMs: Long?,
    positionMs: Long?,
    progress: Boolean,
    playbackSessionId: String?,
) {
    val plugin = registry.state.value.plugin(played.pluginId) ?: return
    if (progress && plugin.manifest.api.target < 8) return
    if (plugin.manifest.roles.audio
            ?.reportPlayback != true
    ) {
        return
    }
    val request =
        ReportPlaybackRequest(
            played.track.ref,
            played.stream.trackingToken,
            playedMs,
            durationMs,
            positionMs = positionMs.takeIf { plugin.manifest.api.target >= 8 },
            playbackSessionId = playbackSessionId.takeIf { plugin.manifest.api.target >= 8 },
        )
    val send: suspend () -> Unit = {
        val trace = PlaybackTrace.start(TraceEvent.HISTORY_REPORT_STARTED, TraceCategory.HISTORY)
        try {
            host.call(played.pluginId, PluginOperations.reportListen, request)
            trace.event(TraceEvent.HISTORY_REPORT_FINISHED, TraceField.SUCCESS to 1L)
        } catch (failure: Exception) {
            trace.event(TraceEvent.HISTORY_REPORT_FINISHED, TraceField.SUCCESS to 0L)
            throw failure
        }
    }
    if (plugin.manifest.api.target >= 8) played.playbackReports.report(request, send) else send()
}
