package io.github.aedev.flow.plugin.smoke

import android.os.SystemClock
import io.github.aedev.flow.player.diagnostics.PlaybackTrace
import io.github.aedev.flow.player.diagnostics.TraceCategory
import io.github.aedev.flow.player.diagnostics.TraceEvent
import io.github.aedev.flow.player.diagnostics.TraceField
import java.io.IOException

/** Read the safe trace continuously so unrelated device logs cannot evict startup evidence. */
internal class SmartTubeLiveTrace : AutoCloseable {
    private val startedMs = SystemClock.elapsedRealtime()
    private val process =
        ProcessBuilder("logcat", "-v", "raw", "-s", "MilkbeatTrace:I", "*:S")
            .redirectErrorStream(true)
            .start()
    private val retained = mutableListOf<String>()
    private val safeLine =
        Regex(
            "^t_ms=[0-9]+ event=(?:${TraceEvent.entries.joinToString("|") { it.name.lowercase() }})" +
                "(?: category=(?:${TraceCategory.entries.joinToString("|") { it.name.lowercase() }}))?" +
                "(?: (?:${TraceField.entries.joinToString("|") { it.name.lowercase() }})=-?[0-9]+)*$",
        )
    private val timestamp = Regex("^t_ms=([0-9]+) ")

    @Volatile private var stopping = false

    @Volatile private var overflow = false

    @Volatile private var failed = false

    @Volatile var ready = false
        private set

    private val worker =
        Thread({
            try {
                process.inputStream.bufferedReader().use { reader ->
                    while (!stopping) {
                        val line = reader.readLine() ?: break
                        if (!safeLine.matches(line) || timeOf(line) < startedMs) continue
                        synchronized(retained) {
                            if (retained.size < MAX_LINES) retained += line else overflow = true
                        }
                        if ("event=logging_enabled" in line) ready = true
                    }
                }
                if (!stopping) failed = true
            } catch (_: IOException) {
                if (!stopping) failed = true
            }
        }, "known-id-safe-trace").apply {
            isDaemon = true
            start()
        }

    init {
        PlaybackTrace.event(TraceEvent.LOGGING_ENABLED)
    }

    fun linesBetween(
        startMs: Long,
        endMs: Long,
    ): List<String> {
        check(!failed) { "Live playback trace ended before playback finished" }
        check(!overflow) { "Live playback trace exceeded its bounded evidence budget" }
        return synchronized(retained) { retained.filter { timeOf(it) in startMs..endMs } }
    }

    override fun close() {
        stopping = true
        try {
            process.destroy()
            try {
                process.inputStream.close()
            } finally {
                try {
                    process.errorStream.close()
                } finally {
                    process.outputStream.close()
                }
            }
        } finally {
            worker.interrupt()
            worker.join(2000)
            check(!worker.isAlive) { "Live playback trace collector did not stop" }
        }
    }

    private fun timeOf(line: String): Long =
        timestamp
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull() ?: -1L

    private companion object {
        const val MAX_LINES = 8192
    }
}
