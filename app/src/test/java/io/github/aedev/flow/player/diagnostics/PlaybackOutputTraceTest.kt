package io.github.aedev.flow.player.diagnostics

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.audio.AudioOutputProbe
import io.github.aedev.flow.player.audio.AudioOutputSample
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

@OptIn(UnstableApi::class)
class PlaybackOutputTraceTest {
    private val time = AnalyticsListener.EventTime(1_000L, Timeline.EMPTY, 2, null, 500L, Timeline.EMPTY, 1, null, 400L, 2_000L)

    @Test
    fun `disabled output callbacks do not inspect the output track`() {
        val listener =
            PlaybackOutputTrace(
                probe {
                    error("Must not sample AudioTrack")
                },
                PlaybackTraceLogger({ error("Must not read clock") }, { error("Must not emit") }),
            )
        listener.onAudioUnderrun(time, 512, 100L, 50L)
        listener.onAudioTrackReleased(time, AudioSink.AudioTrackConfig(2, 48_000, 12, false, false, 512))
    }

    @Test
    fun `underrun and track release carry output sample and preserve failed window identity`() {
        val lines = mutableListOf<String>()
        val logger = PlaybackTraceLogger({ 1_050L }, lines::add)
        logger.setEnabled(true)
        val listener = PlaybackOutputTrace(probe { AudioOutputSample(7L, 48_000L) }, logger)
        listener.onAudioUnderrun(time, 512, 100L, 50L)
        listener.onAudioTrackReleased(time, AudioSink.AudioTrackConfig(2, 48_000, 12, false, false, 512))
        assertThat(lines[1]).contains("event=audio_underrun")
        assertThat(lines[1]).contains("window_index=2 current_window_index=1")
        assertThat(lines[1]).contains("generation=7 head_frames=48000")
        assertThat(lines[1]).contains("buffer_bytes=512 buffer_ms=100 since_last_feed_ms=50")
        assertThat(lines[2]).contains("event=audio_track_released")
        assertThat(lines[2]).contains("sample_rate=48000 encoding=2")
    }

    @Test
    fun `video format logs fixed codec family and dimensions without arbitrary format metadata`() {
        val lines = mutableListOf<String>()
        val logger = PlaybackTraceLogger({ 1_050L }, lines::add)
        logger.setEnabled(true)
        val listener = PlaybackOutputTrace(trace = logger)
        listener.onVideoInputFormatChanged(
            time,
            Format
                .Builder()
                .setSampleMimeType("video/x-vnd.on2.vp9")
                .setId("secret-id")
                .setLabel("private-title")
                .setWidth(3_840)
                .setHeight(2_160)
                .build(),
            null,
        )
        assertThat(lines[1]).contains("category=video_vp9")
        assertThat(lines[1]).contains("width=3840 height=2160")
        assertThat(lines.joinToString()).doesNotContain("secret-id")
        assertThat(lines.joinToString()).doesNotContain("private-title")
    }

    @Test
    fun `renderer readiness identifies the stalled type alongside continuing PCM output`() {
        val lines = mutableListOf<String>()
        val logger = PlaybackTraceLogger({ 1_050L }, lines::add)
        logger.setEnabled(true)
        val listener = PlaybackOutputTrace(probe { AudioOutputSample(7L, 48_000L) }, logger)
        listener.onRendererReadyChanged(time, 0, androidx.media3.common.C.TRACK_TYPE_VIDEO, false)
        assertThat(lines[1]).contains("event=renderer_ready")
        assertThat(lines[1]).contains("track_type=2 ready=0")
        assertThat(lines[1]).contains("generation=7 head_frames=48000")
    }

    private fun probe(read: () -> AudioOutputSample?): AudioOutputProbe =
        object : AudioOutputProbe {
            override val changes = MutableStateFlow(0L)
            override val monitorable = true

            override fun read() = read.invoke()
        }
}
