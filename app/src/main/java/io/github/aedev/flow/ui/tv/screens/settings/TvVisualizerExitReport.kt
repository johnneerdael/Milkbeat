package io.github.aedev.flow.ui.tv.screens.settings

import android.content.res.Resources
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.player.audio.visualizer.AppExit
import io.github.aedev.flow.player.audio.visualizer.EngineTrailLine
import io.github.aedev.flow.player.audio.visualizer.ExitPresence
import io.github.aedev.flow.player.audio.visualizer.exitForTrailLine
import io.github.aedev.flow.player.audio.visualizer.parseEngineTrail
import io.github.aedev.flow.ui.tv.components.TvCard

/** What the Last exit row and its report are built from, read off the main thread. */
internal data class ExitRecords(
    val exits: List<AppExit>,
    val trail: String,
)

/** The device and the troubleshooting switches as they are now, for the report's header. */
internal data class ExitReportDevice(
    val manufacturer: String,
    val model: String,
    val release: String,
    val sdk: Int,
    val gpu: String,
    val backgroundCompile: Boolean,
    val shaderBinaryCache: Boolean,
)

internal data class ExitReportEntry(
    val title: String,
    val details: List<String>,
)

internal data class ExitReport(
    val header: List<String>,
    val entries: List<ExitReportEntry>,
) {
    fun asText(): String = (header + entries.flatMap { listOf("", it.title) + it.details.map { detail -> "  $detail" } }).joinToString("\n")
}

/** Settings › Visualizations › Last exit: the latest exits and what the engine was doing then. */
internal class VisualizerExitReportFormatter(
    private val resources: Resources,
) {
    /** The Last exit row's value: the latest exit that was a problem or happened on screen. */
    fun summary(
        exits: List<AppExit>,
        sdk: Int,
        nowMs: Long,
    ): String {
        if (sdk < Build.VERSION_CODES.R) return resources.getString(R.string.visualizer_last_exit_needs_android_11)
        val exit =
            exits.firstOrNull { it.isProblem || it.presence == ExitPresence.ON_SCREEN }
                ?: return resources.getString(R.string.visualizer_last_exit_none)
        val reason = reasonLabel(exit.reason, exit.status, sdk).replaceFirstChar { it.uppercaseChar() }
        return resources.getString(R.string.visualizer_last_exit_summary, reason, age(nowMs, exit.timestampMs))
    }

    fun report(
        records: ExitRecords,
        device: ExitReportDevice,
        nowMs: Long,
    ): ExitReport {
        val supported = device.sdk >= Build.VERSION_CODES.R
        val header =
            buildList {
                add(
                    resources.getString(
                        R.string.visualizer_exit_report_device,
                        device.manufacturer,
                        device.model,
                        device.release,
                        device.sdk,
                    ),
                )
                add(
                    resources.getString(
                        R.string.visualizer_exit_report_gpu,
                        device.gpu.ifEmpty { resources.getString(R.string.visualizer_exit_report_gpu_unknown) },
                    ),
                )
                add(
                    resources.getString(
                        R.string.visualizer_exit_report_now,
                        onOff(device.backgroundCompile),
                        onOff(device.shaderBinaryCache),
                    ),
                )
                when {
                    !supported -> add(resources.getString(R.string.visualizer_exit_report_needs_android_11))
                    records.exits.isEmpty() -> add(resources.getString(R.string.visualizer_exit_report_no_exits))
                }
            }
        val lines = parseEngineTrail(records.trail)
        val owners = lines.map { exitForTrailLine(records.exits, it) }
        val entries =
            records.exits.mapIndexed { index, exit ->
                val owned = lines.filterIndexed { line, _ -> owners[line] == index }
                ExitReportEntry(
                    title =
                        resources.getString(
                            R.string.visualizer_exit_report_entry,
                            age(nowMs, exit.timestampMs),
                            reasonLabel(exit.reason, exit.status, device.sdk),
                            presenceLabel(exit.presence),
                        ),
                    details =
                        buildList {
                            if (exit.description.isNotEmpty()) add(exit.description)
                            if (exit.pssKb > 0 || exit.rssKb > 0) {
                                add(
                                    resources.getString(
                                        R.string.visualizer_exit_report_memory,
                                        exit.pssKb / KB_PER_MB,
                                        exit.rssKb / KB_PER_MB,
                                    ),
                                )
                            }
                            // The switches of the process that ended, which today's settings may differ from.
                            newestSwitches(owned)?.let(::add)
                            owned.forEach { line ->
                                add(
                                    resources.getString(
                                        R.string.visualizer_exit_report_trail,
                                        line.thread,
                                        line.message,
                                        ((exit.timestampMs - line.timeMs) / MS_PER_SECOND).toInt(),
                                    ),
                                )
                            }
                        },
                )
            }
        val withoutRecords =
            if (supported || lines.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    ExitReportEntry(
                        title = resources.getString(R.string.visualizer_exit_report_last_activity),
                        details =
                            listOfNotNull(newestSwitches(lines)) +
                                lines.map { resources.getString(R.string.visualizer_exit_report_last_trail, it.thread, it.message) },
                    ),
                )
            }
        return ExitReport(header, entries + withoutRecords)
    }

    fun age(
        nowMs: Long,
        thenMs: Long,
    ): String {
        val seconds = maxOf(0L, (nowMs - thenMs) / MS_PER_SECOND)
        return when {
            seconds < 90 -> resources.getString(R.string.visualizer_age_seconds, seconds.toInt())
            seconds < 90 * 60 -> resources.getString(R.string.visualizer_age_minutes, ((seconds + 30) / 60).toInt())
            seconds < 36 * 3600 -> resources.getString(R.string.visualizer_age_hours, ((seconds + 1800) / 3600).toInt())
            else -> resources.getString(R.string.visualizer_age_days, ((seconds + 43200) / 86400).toInt())
        }
    }

    /** Labels follow ApplicationExitInfo; [sdk] matters because Android 14 split reason 10. */
    fun reasonLabel(
        reason: Int,
        status: Int,
        sdk: Int,
    ): String =
        when (reason) {
            AppExit.REASON_EXIT_SELF -> {
                resources.getString(R.string.visualizer_exit_reason_exit_self, status)
            }

            AppExit.REASON_SIGNALED -> {
                SIGNAL_NAMES[status]?.let { resources.getString(R.string.visualizer_exit_reason_signaled_named, status, it) }
                    ?: resources.getString(R.string.visualizer_exit_reason_signaled, status)
            }

            else -> {
                resources.getString(reasonLabelId(reason, sdk))
            }
        }

    private fun reasonLabelId(
        reason: Int,
        sdk: Int,
    ): Int =
        when (reason) {
            AppExit.REASON_LOW_MEMORY -> {
                R.string.visualizer_exit_reason_low_memory
            }

            AppExit.REASON_CRASH -> {
                R.string.visualizer_exit_reason_crash
            }

            AppExit.REASON_CRASH_NATIVE -> {
                R.string.visualizer_exit_reason_crash_native
            }

            AppExit.REASON_ANR -> {
                R.string.visualizer_exit_reason_anr
            }

            AppExit.REASON_INITIALIZATION_FAILURE -> {
                R.string.visualizer_exit_reason_initialization_failure
            }

            AppExit.REASON_PERMISSION_CHANGE -> {
                R.string.visualizer_exit_reason_permission_change
            }

            AppExit.REASON_EXCESSIVE_RESOURCE_USAGE -> {
                R.string.visualizer_exit_reason_excessive_resource_usage
            }

            AppExit.REASON_USER_REQUESTED -> {
                if (sdk >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    R.string.visualizer_exit_reason_user_requested
                } else {
                    R.string.visualizer_exit_reason_user_requested_legacy
                }
            }

            AppExit.REASON_USER_STOPPED -> {
                R.string.visualizer_exit_reason_user_stopped
            }

            AppExit.REASON_DEPENDENCY_DIED -> {
                R.string.visualizer_exit_reason_dependency_died
            }

            AppExit.REASON_OTHER -> {
                R.string.visualizer_exit_reason_other
            }

            AppExit.REASON_FREEZER -> {
                R.string.visualizer_exit_reason_freezer
            }

            AppExit.REASON_PACKAGE_STATE_CHANGE -> {
                R.string.visualizer_exit_reason_package_state_change
            }

            AppExit.REASON_PACKAGE_UPDATED -> {
                R.string.visualizer_exit_reason_package_updated
            }

            else -> {
                R.string.visualizer_exit_reason_unknown
            }
        }

    private fun presenceLabel(presence: ExitPresence): String =
        resources.getString(
            when (presence) {
                ExitPresence.ON_SCREEN -> R.string.visualizer_exit_on_screen
                ExitPresence.PLAYING -> R.string.visualizer_exit_playing
                ExitPresence.BACKGROUND -> R.string.visualizer_exit_background
            },
        )

    private fun newestSwitches(lines: List<EngineTrailLine>): String? {
        val newest = lines.filter { it.shaderBinaryCache != null && it.backgroundCompile != null }.maxByOrNull { it.timeMs } ?: return null
        return resources.getString(
            R.string.visualizer_exit_report_switches,
            onOff(newest.shaderBinaryCache == true),
            onOff(newest.backgroundCompile == true),
        )
    }

    private fun onOff(enabled: Boolean) =
        resources.getString(if (enabled) R.string.visualizer_switch_on else R.string.visualizer_switch_off)

    private companion object {
        const val KB_PER_MB = 1024
        const val MS_PER_SECOND = 1000L

        val SIGNAL_NAMES = mapOf(4 to "SIGILL", 6 to "SIGABRT", 7 to "SIGBUS", 8 to "SIGFPE", 9 to "SIGKILL", 11 to "SIGSEGV")
    }
}

/** The report in the side panel; each exit is a focusable card so the D-pad scrolls through them. */
@Composable
internal fun VisualizerExitReportList(report: ExitReport) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "header") { ExitReportCard(title = null, lines = report.header) }
        itemsIndexed(report.entries, key = { index, _ -> index }) { _, entry ->
            ExitReportCard(title = entry.title, lines = entry.details)
        }
    }
}

@Composable
private fun ExitReportCard(
    title: String?,
    lines: List<String>,
) {
    TvCard(onClick = {}, modifier = Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
        }
        if (lines.isNotEmpty()) {
            Text(
                text = lines.joinToString("\n"),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}
