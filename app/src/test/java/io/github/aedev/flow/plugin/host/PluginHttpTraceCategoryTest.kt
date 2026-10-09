package io.github.aedev.flow.plugin.host

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.diagnostics.PlaybackTraceLogger
import io.github.aedev.flow.player.diagnostics.TraceCategory
import io.github.aedev.flow.player.diagnostics.TraceEvent
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Test

class PluginHttpTraceCategoryTest {
    @Test
    fun `sensitive URLs reduce to fixed endpoint category and cannot reach the log sink`() {
        val lines = mutableListOf<String>()
        val logger = PlaybackTraceLogger({ 100L }, lines::add)
        logger.setEnabled(true)
        val url = "https://music.youtube.com/youtubei/v1/player?key=secret-token&videoId=private-id".toHttpUrl()
        val category = pluginHttpTraceCategory(url)
        logger.start(TraceEvent.HTTP_STARTED, category).event(TraceEvent.HTTP_FINISHED)
        assertThat(category).isEqualTo(TraceCategory.YOUTUBE_PLAYER)
        assertThat(lines.joinToString()).doesNotContain("secret-token")
        assertThat(lines.joinToString()).doesNotContain("private-id")
        assertThat(lines.joinToString()).doesNotContain("youtube.com")
        assertThat(
            pluginHttpTraceCategory("https://r1.googlevideo.com/videoplayback?sig=secret".toHttpUrl()),
        ).isEqualTo(TraceCategory.MEDIA_DELIVERY)
        assertThat(
            pluginHttpTraceCategory("https://youtube.com.example.net/youtubei/v1/player?auth=secret".toHttpUrl()),
        ).isEqualTo(TraceCategory.OTHER_HTTP)
    }
}
