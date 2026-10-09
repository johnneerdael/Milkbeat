package io.github.aedev.flow.player.audio.visualizer

/** One of Android's exit records for this app, independent of the framework class. */
data class AppExit(
    val pid: Int,
    val reason: Int,
    val status: Int,
    val importance: Int,
    val timestampMs: Long,
    val pssKb: Long,
    val rssKb: Long,
    val description: String,
    // From the process's state summary; null when the process recorded none.
    val session: String? = null,
) {
    val presence: ExitPresence
        get() =
            when (importance) {
                IMPORTANCE_FOREGROUND, IMPORTANCE_VISIBLE -> ExitPresence.ON_SCREEN
                IMPORTANCE_FOREGROUND_SERVICE, IMPORTANCE_PERCEPTIBLE -> ExitPresence.PLAYING
                else -> ExitPresence.BACKGROUND
            }

    val isProblem: Boolean get() = reason in PROBLEM_REASONS

    companion object {
        // ApplicationExitInfo.REASON_* and RunningAppProcessInfo.IMPORTANCE_*, as literals so the
        // report is testable on the JVM.
        const val REASON_EXIT_SELF = 1
        const val REASON_SIGNALED = 2
        const val REASON_LOW_MEMORY = 3
        const val REASON_CRASH = 4
        const val REASON_CRASH_NATIVE = 5
        const val REASON_ANR = 6
        const val REASON_INITIALIZATION_FAILURE = 7
        const val REASON_PERMISSION_CHANGE = 8
        const val REASON_EXCESSIVE_RESOURCE_USAGE = 9
        const val REASON_USER_REQUESTED = 10
        const val REASON_USER_STOPPED = 11
        const val REASON_DEPENDENCY_DIED = 12
        const val REASON_OTHER = 13
        const val REASON_FREEZER = 14
        const val REASON_PACKAGE_STATE_CHANGE = 15
        const val REASON_PACKAGE_UPDATED = 16

        const val IMPORTANCE_FOREGROUND = 100
        const val IMPORTANCE_FOREGROUND_SERVICE = 125
        const val IMPORTANCE_VISIBLE = 200
        const val IMPORTANCE_PERCEPTIBLE = 230

        private val PROBLEM_REASONS =
            setOf(
                REASON_SIGNALED,
                REASON_LOW_MEMORY,
                REASON_CRASH,
                REASON_CRASH_NATIVE,
                REASON_ANR,
                REASON_INITIALIZATION_FAILURE,
                REASON_EXCESSIVE_RESOURCE_USAGE,
            )
    }
}

/** Where the app was when it ended: Milkbeat keeps playing from a foreground service off screen. */
enum class ExitPresence { ON_SCREEN, PLAYING, BACKGROUND }

/** A line of the engine's trail file: which thread, its process, when, its switches and what it was doing. */
data class EngineTrailLine(
    val thread: String,
    val pid: Int,
    val timeMs: Long,
    val session: String?,
    // The Shader binary cache and Background compile states of that process; null when not recorded.
    val shaderBinaryCache: Boolean?,
    val backgroundCompile: Boolean?,
    val message: String,
)

/**
 * Parses the trail ProjectM-TV's engine writes, one line per thread:
 * `<thread> pid=<pid> ms=<ms> session=<id> cache=on|off compile=on|off <message>`.
 * pid and ms are required; lines cut short by a crash are dropped.
 */
fun parseEngineTrail(text: String): List<EngineTrailLine> = text.lineSequence().mapNotNull(::parseTrailLine).toList()

private fun parseTrailLine(raw: String): EngineTrailLine? {
    val words = raw.trim().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    val fields = mutableMapOf<String, String>()
    var index = 1
    while (index < words.size) {
        val key = TRAIL_KEYS.firstOrNull { words[index].startsWith("$it=") } ?: break
        fields[key] = words[index].substring(key.length + 1)
        index++
    }
    val message = words.drop(index).joinToString(" ")
    val pid = fields["pid"]?.toIntOrNull() ?: return null
    val ms = fields["ms"]?.toLongOrNull() ?: return null
    if (message.isEmpty()) return null
    val cache = fields["cache"]?.let(::onOff)
    val compile = fields["compile"]?.let(::onOff)
    val recorded = cache != null && compile != null
    return EngineTrailLine(
        thread = words[0],
        pid = pid,
        timeMs = ms,
        session = fields["session"]?.takeUnless { it == "-" },
        shaderBinaryCache = cache.takeIf { recorded },
        backgroundCompile = compile.takeIf { recorded },
        message = message,
    )
}

private val TRAIL_KEYS = listOf("pid", "ms", "session", "cache", "compile")

private fun onOff(value: String): Boolean? =
    when (value) {
        "on" -> true
        "off" -> false
        else -> null
    }

/**
 * The exit a trail line belongs to, or -1. An exit that recorded a session must carry the line's
 * session; otherwise the earliest exit of the same PID at or after the line, as Android reuses PIDs.
 */
fun exitForTrailLine(
    exits: List<AppExit>,
    line: EngineTrailLine,
): Int {
    var best = -1
    exits.forEachIndexed { index, exit ->
        if (exit.session != null && exit.session != line.session) return@forEachIndexed
        if (exit.pid != line.pid || exit.timestampMs < line.timeMs) return@forEachIndexed
        if (best < 0 || exit.timestampMs < exits[best].timestampMs) best = index
    }
    return best
}
