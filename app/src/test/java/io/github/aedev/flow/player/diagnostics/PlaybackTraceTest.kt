package io.github.aedev.flow.player.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlaybackTraceTest {
    @Test
    fun `disabled logging never reads the clock or invokes the sink`() {
        val logger = PlaybackTraceLogger({ error("Clock must stay idle") }, { error("Must not emit") })
        logger.event(TraceEvent.PLAY)
        logger
            .start(TraceEvent.RUNTIME_QUEUED, TraceCategory.AUDIO_RESOLVE)
            .event(TraceEvent.RUNTIME_FINISHED)
    }

    @Test
    fun `spans correlate queued and executed work with monotonic durations`() {
        val lines = mutableListOf<String>()
        var now = 100L
        val logger = PlaybackTraceLogger({ now }, lines::add)
        logger.setEnabled(true)
        val span = logger.start(TraceEvent.RUNTIME_QUEUED, TraceCategory.AUDIO_RESOLVE)
        now = 2_100L
        span.event(TraceEvent.RUNTIME_EXECUTION_STARTED, TraceField.QUEUE_WAIT_MS to 2_000L)
        now = 2_245L
        span.event(TraceEvent.RUNTIME_EXECUTION_FINISHED, TraceField.EXECUTION_MS to 145L, TraceField.SUCCESS to 1L)
        assertThat(lines[1]).contains("trace_id=1 elapsed_ms=0")
        assertThat(lines[2]).contains("trace_id=1 elapsed_ms=2000 queue_wait_ms=2000")
        assertThat(lines[3]).contains("trace_id=1 elapsed_ms=2145 execution_ms=145 success=1")
        assertThat(lines[3]).contains("category=audio_resolve")
    }

    @Test
    fun `turning off immediately suppresses in-flight completion and later events`() {
        val lines = mutableListOf<String>()
        val logger = PlaybackTraceLogger({ 0L }, lines::add)
        logger.setEnabled(true)
        val span = logger.start(TraceEvent.HTTP_STARTED, TraceCategory.YOUTUBE_PLAYER)
        val before = lines.size
        logger.setEnabled(false)
        span.event(TraceEvent.HTTP_FINISHED)
        logger.event(TraceEvent.PAUSE)
        assertThat(lines).hasSize(before)
    }

    @Test
    fun `an operation started while disabled stays silent after enabling`() {
        val lines = mutableListOf<String>()
        val logger = PlaybackTraceLogger({ 0L }, lines::add)
        val span = logger.start(TraceEvent.RUNTIME_QUEUED, TraceCategory.SEARCH)
        logger.setEnabled(true)
        val before = lines.size
        span.event(TraceEvent.RUNTIME_EXECUTION_FINISHED)
        assertThat(lines).hasSize(before)
    }
}
