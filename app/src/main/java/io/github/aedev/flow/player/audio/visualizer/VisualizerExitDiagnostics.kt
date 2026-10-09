package io.github.aedev.flow.player.audio.visualizer

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nl.neerdael.projectm.core.ProjectMJNI
import java.io.File
import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Why the app's process ended before, without adb: Android's exit records (Android 11 and later)
 * next to the engine's trail file, which says what its render and background compile threads were
 * last doing. The trail stays in no-backup storage, so preset names never leave the TV.
 */
@Singleton
class VisualizerExitDiagnostics
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val mutex = Mutex()
        private var opened = false

        /** The previous processes' trail, as it was before this process's engine started writing it. */
        @Volatile
        var previousTrail: String = ""
            private set

        /**
         * Keeps the earlier trail, then hands the file to the engine. Must run before the engine first
         * renders in this process; later calls do nothing.
         */
        suspend fun open() =
            mutex.withLock {
                if (opened) return@withLock
                opened = true
                withContext(Dispatchers.IO) {
                    val file = trailFile()
                    previousTrail = read(file)
                    // A random ID in this process's exit record and in its trail lines, as Android reuses PIDs.
                    val session = java.lang.Long.toHexString(SecureRandom().nextLong())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        runCatching {
                            context
                                .getSystemService(
                                    ActivityManager::class.java,
                                ).setProcessStateSummary(session.toByteArray(Charsets.US_ASCII))
                        }.onFailure { Log.w(TAG, "Could not record the process session", it) }
                    }
                    ProjectMJNI.setDiagnosticsFile(file.absolutePath, session)
                }
            }

        /** The trail to report: the earlier processes' once the engine has the file, otherwise the file itself. */
        suspend fun trail(): String =
            mutex.withLock {
                if (opened) previousTrail else withContext(Dispatchers.IO) { read(trailFile()) }
            }

        /** The app's latest exits, newest first; empty before Android 11. */
        suspend fun recentExits(): List<AppExit> {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
            return withContext(Dispatchers.IO) {
                runCatching {
                    context
                        .getSystemService(ActivityManager::class.java)
                        .getHistoricalProcessExitReasons(context.packageName, 0, MAX_EXITS)
                        .map { info ->
                            AppExit(
                                pid = info.pid,
                                reason = info.reason,
                                status = info.status,
                                importance = info.importance,
                                timestampMs = info.timestamp,
                                pssKb = info.pss,
                                rssKb = info.rss,
                                description = info.description.orEmpty(),
                                session = info.processStateSummary?.takeIf { it.isNotEmpty() }?.toString(Charsets.US_ASCII),
                            )
                        }
                }.onFailure { Log.w(TAG, "Exit records unavailable", it) }
                    .getOrDefault(emptyList())
            }
        }

        private fun trailFile() = File(context.noBackupFilesDir, TRAIL_FILE)

        private fun read(file: File): String =
            runCatching {
                if (!file.isFile) return@runCatching ""
                file.inputStream().use { input ->
                    val data = ByteArray(MAX_TRAIL_BYTES)
                    var total = 0
                    while (total < data.size) {
                        val n = input.read(data, total, data.size - total)
                        if (n < 0) break
                        total += n
                    }
                    String(data, 0, total, Charsets.UTF_8)
                }
            }.onFailure { Log.w(TAG, "Could not read $file", it) }
                .getOrDefault("")

        private companion object {
            const val TAG = "VisualizerExitDiag"
            const val TRAIL_FILE = "engine_trail.txt"
            const val MAX_EXITS = 5
            const val MAX_TRAIL_BYTES = 16 * 1024
        }
    }
