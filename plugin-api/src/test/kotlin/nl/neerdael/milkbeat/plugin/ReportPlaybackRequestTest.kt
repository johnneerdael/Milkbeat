package nl.neerdael.milkbeat.plugin

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import org.junit.Test

class ReportPlaybackRequestTest {
    @Test
    fun `legacy listen decodes without actual position or session identity`() {
        val request =
            PluginJson.decodeFromString(
                ReportPlaybackRequest.serializer(),
                """{"entity":{"kind":"TRACK","providerId":"id"},"playedMs":30000}""",
            )
        assertThat(request.positionMs).isNull()
        assertThat(request.playbackSessionId).isNull()
    }

    @Test
    fun `seek position remains independent from cumulative listening time`() {
        val request =
            ReportPlaybackRequest(
                EntityRef(EntityKind.TRACK, "id"),
                playedMs = 30_000L,
                positionMs = 600_000L,
                playbackSessionId = "listen-1",
            )
        val decoded =
            PluginJson.decodeFromString(
                ReportPlaybackRequest.serializer(),
                PluginJson.encodeToString(ReportPlaybackRequest.serializer(), request),
            )
        assertThat(decoded).isEqualTo(request)
        assertThat(decoded.positionMs).isEqualTo(600_000L)
        assertThat(decoded.playedMs).isEqualTo(30_000L)
    }
}
