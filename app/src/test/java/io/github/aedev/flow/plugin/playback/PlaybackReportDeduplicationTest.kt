package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.plugin.ReportPlaybackRequest
import org.junit.Test
import java.io.IOException

class PlaybackReportDeduplicationTest {
    private val request = ReportPlaybackRequest(EntityRef(EntityKind.TRACK, "id"), "token", 30_000L, 600_000L, 30_000L)

    @Test
    fun `identical successful progress and final are reported once`() =
        runTest {
            val guard = PlaybackReportDeduplication()
            var calls = 0
            guard.report(request) { calls++ }
            guard.report(request) { calls++ }
            assertThat(calls).isEqualTo(1)
        }

    @Test
    fun `seek baseline with unchanged listening time is retained`() =
        runTest {
            val guard = PlaybackReportDeduplication()
            var calls = 0
            guard.report(request) { calls++ }
            guard.report(request.copy(positionMs = 300_000L)) { calls++ }
            guard.report(request.copy(playbackSessionId = "replay")) { calls++ }
            assertThat(calls).isEqualTo(3)
        }

    @Test
    fun `failed sample remains retryable and another resolution has its own state`() =
        runTest {
            val guard = PlaybackReportDeduplication()
            try {
                guard.report(request) { throw IOException("offline") }
            } catch (_: IOException) {
            }
            var calls = 0
            guard.report(request) { calls++ }
            PlaybackReportDeduplication().report(request) { calls++ }
            assertThat(calls).isEqualTo(2)
        }
}
