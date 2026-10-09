package io.github.aedev.flow.plugin.playback

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest

/** Keep successful progress/final samples idempotent within this accepted resolution only. */
internal class PlaybackReportDeduplication {
    private val mutex = Mutex()
    private var reported: ReportPlaybackRequest? = null

    suspend fun report(
        request: ReportPlaybackRequest,
        send: suspend () -> Unit,
    ) {
        mutex.withLock {
            if (reported == request) return
            send()
            reported = request
        }
    }
}
