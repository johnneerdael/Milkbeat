package io.github.aedev.flow.ui.tv.screens.settings

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.player.audio.visualizer.AppExit
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class VisualizerExitReportFormatterTest {
    private val formatter = VisualizerExitReportFormatter(ApplicationProvider.getApplicationContext<Application>().resources)

    @Test
    fun `summary shows the latest problem or on-screen exit`() {
        val exits =
            listOf(
                exit(reason = AppExit.REASON_USER_REQUESTED, importance = 400, timestampMs = NOW - 1_000),
                exit(reason = AppExit.REASON_SIGNALED, status = 11, importance = 400, timestampMs = NOW - 5 * 60_000),
            )

        assertThat(formatter.summary(exits, 34, NOW)).isEqualTo("Killed by signal 11 (SIGSEGV) · 5 min ago")
        assertThat(formatter.summary(exits.take(1), 34, NOW)).isEqualTo("None recorded")
        assertThat(formatter.summary(exits, 29, NOW)).isEqualTo("Needs Android 11")
    }

    @Test
    fun `reason 10 depends on the Android version`() {
        assertThat(formatter.reasonLabel(AppExit.REASON_USER_REQUESTED, 0, 34))
            .isEqualTo("stopped by the user (force stop or removed from Recents)")
        assertThat(formatter.reasonLabel(AppExit.REASON_USER_REQUESTED, 0, 33))
            .isEqualTo("stopped by request (force stop, Recents, app update or component change)")
        assertThat(formatter.reasonLabel(99, 0, 34)).isEqualTo("unknown reason")
    }

    @Test
    fun `each exit shows the switches and trail of the process that ended`() {
        val trail =
            """
            render pid=42 ms=${NOW - 63_000} session=s1 cache=on compile=on showing A.milk
            render pid=42 ms=${NOW - 62_000} session=s1 cache=off compile=on blending into B.milk
            prewarm pid=42 ms=${NOW - 65_000} session=s1 cache=off compile=on compiling C.milk
            render pid=42 ms=${NOW - 30_000} session=s2 cache=on compile=on showing D.milk
            """.trimIndent()
        val records =
            ExitRecords(
                exits =
                    listOf(
                        exit(pid = 42, importance = 125, timestampMs = NOW - 60_000, session = "s1", pssKb = 204_800, rssKb = 307_200),
                    ),
                trail = trail,
            )

        val report = formatter.report(records, device(backgroundCompile = true, shaderBinaryCache = true), NOW)

        assertThat(report.header)
            .containsExactly(
                "Amazon AFTKRT, Android 11 (API 30)",
                "GPU: PowerVR Rogue GE9215",
                "Now: background compile on, shader binary cache on",
            ).inOrder()
        val entry = report.entries.single()
        assertThat(entry.title).isEqualTo("60 s ago: crashed (native code), playing in the background")
        assertThat(entry.details)
            .containsExactly(
                "Memory: 200 MB PSS, 300 MB RSS",
                "Switches: shader binary cache off, background compile on",
                "render: showing A.milk (3 s before)",
                "render: blending into B.milk (2 s before)",
                "prewarm: compiling C.milk (5 s before)",
            ).inOrder()
    }

    @Test
    fun `before Android 11 the report shows the last engine activity`() {
        val records = ExitRecords(emptyList(), "render pid=1 ms=5 cache=on compile=off showing A.milk")

        val report = formatter.report(records, device(sdk = 28, gpu = ""), NOW)

        assertThat(report.header).contains("Android 11 or later is needed for exit records.")
        assertThat(report.header).contains("GPU: not known until the visualizer has shown")
        assertThat(report.entries.single().details)
            .containsExactly("Switches: shader binary cache on, background compile off", "render: showing A.milk")
            .inOrder()
    }

    @Test
    fun `ages round to the nearest unit`() {
        assertThat(formatter.age(NOW, NOW - 89_000)).isEqualTo("89 s ago")
        assertThat(formatter.age(NOW, NOW - 150 * 60_000L)).isEqualTo("3 h ago")
        assertThat(formatter.age(NOW, NOW - 3 * 86_400_000L)).isEqualTo("3 d ago")
        assertThat(formatter.age(NOW, NOW + 5_000)).isEqualTo("0 s ago")
    }

    private fun exit(
        pid: Int = 1,
        reason: Int = AppExit.REASON_CRASH_NATIVE,
        status: Int = 0,
        importance: Int = AppExit.IMPORTANCE_FOREGROUND,
        timestampMs: Long = NOW,
        session: String? = null,
        pssKb: Long = 0,
        rssKb: Long = 0,
    ) = AppExit(pid, reason, status, importance, timestampMs, pssKb, rssKb, "", session)

    private fun device(
        sdk: Int = 30,
        gpu: String = "PowerVR Rogue GE9215",
        backgroundCompile: Boolean = true,
        shaderBinaryCache: Boolean = true,
    ) = ExitReportDevice("Amazon", "AFTKRT", if (sdk >= 30) "11" else "9", sdk, gpu, backgroundCompile, shaderBinaryCache)

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
