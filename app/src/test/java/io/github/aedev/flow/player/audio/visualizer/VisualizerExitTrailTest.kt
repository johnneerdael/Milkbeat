package io.github.aedev.flow.player.audio.visualizer

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VisualizerExitTrailTest {
    @Test
    fun `parses switches, session and message and drops lines cut short`() {
        val lines =
            parseEngineTrail(
                """
                render pid=42 ms=1000 session=abc cache=off compile=on blending into Foo.milk (2560x1440)
                prewarm pid=42 ms=900 session=- idle
                render pid=4
                prewarm pid=x ms=1 compiling
                """.trimIndent(),
            )

        assertThat(lines)
            .containsExactly(
                EngineTrailLine("render", 42, 1000, "abc", false, true, "blending into Foo.milk (2560x1440)"),
                EngineTrailLine("prewarm", 42, 900, null, null, null, "idle"),
            ).inOrder()
    }

    @Test
    fun `switches are recorded only when both are present`() {
        val line = parseEngineTrail("render pid=1 ms=2 cache=on showing A.milk").single()

        assertThat(line.shaderBinaryCache).isNull()
        assertThat(line.backgroundCompile).isNull()
    }

    @Test
    fun `a line belongs to the exit of its session even when the PID was reused`() {
        val line = EngineTrailLine("render", 7, 1_000, "new", true, true, "showing A.milk")
        val exits =
            listOf(
                exit(pid = 7, timestampMs = 5_000, session = "new"),
                exit(pid = 7, timestampMs = 2_000, session = "old"),
            )

        assertThat(exitForTrailLine(exits, line)).isEqualTo(0)
    }

    @Test
    fun `without sessions a line belongs to the earliest exit of its PID at or after it`() {
        val line = EngineTrailLine("render", 7, 1_000, null, null, null, "showing A.milk")
        val exits =
            listOf(
                exit(pid = 7, timestampMs = 9_000),
                exit(pid = 7, timestampMs = 3_000),
                exit(pid = 7, timestampMs = 500),
                exit(pid = 8, timestampMs = 2_000),
            )

        assertThat(exitForTrailLine(exits, line)).isEqualTo(1)
        assertThat(exitForTrailLine(exits.take(1).map { it.copy(pid = 9) }, line)).isEqualTo(-1)
    }

    @Test
    fun `playback from the foreground service counts as playing, not on screen`() {
        assertThat(exit(importance = AppExit.IMPORTANCE_FOREGROUND).presence).isEqualTo(ExitPresence.ON_SCREEN)
        assertThat(exit(importance = AppExit.IMPORTANCE_FOREGROUND_SERVICE).presence).isEqualTo(ExitPresence.PLAYING)
        assertThat(exit(importance = 400).presence).isEqualTo(ExitPresence.BACKGROUND)
        assertThat(exit(reason = AppExit.REASON_CRASH_NATIVE).isProblem).isTrue()
        assertThat(exit(reason = AppExit.REASON_USER_REQUESTED).isProblem).isFalse()
    }

    private fun exit(
        pid: Int = 1,
        reason: Int = AppExit.REASON_CRASH_NATIVE,
        importance: Int = AppExit.IMPORTANCE_FOREGROUND,
        timestampMs: Long = 0,
        session: String? = null,
    ) = AppExit(pid, reason, 0, importance, timestampMs, 0, 0, "", session)
}
