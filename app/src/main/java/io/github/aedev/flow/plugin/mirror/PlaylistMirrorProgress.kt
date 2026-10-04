package io.github.aedev.flow.plugin.mirror

internal fun mirrorPercentage(state: PlaylistMirrorState): Int {
    if (state.ready) return 100
    return when (state.phase) {
        MirrorPhase.SOURCE_LOADING -> 0
        MirrorPhase.MATCHING -> if (state.total <= 0) 85 else portion(state.matched + state.missing, state.total, 85)
        MirrorPhase.WRITING -> 85 + portion(state.phaseCompleted, state.phaseTotal, 10)
        MirrorPhase.VERIFYING -> 95 + portion(state.phaseCompleted, state.phaseTotal, 4)
    }
}

private fun portion(
    completed: Int,
    total: Int,
    range: Int,
): Int = if (total <= 0) 0 else (completed.coerceIn(0, total).toLong() * range / total).toInt()
