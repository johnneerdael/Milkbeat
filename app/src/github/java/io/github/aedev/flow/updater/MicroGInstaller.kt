package io.github.aedev.flow.updater

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.plugin.host.MICROG_PACKAGE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val MICROG_APK_URL = "https://github.com/johnneerdael/MicroG-RE-TV/releases/latest/download/microg-re-tv.apk"
private const val MAX_APK_BYTES = 256L * 1024 * 1024
private const val INSTALL_REQUEST = 7302
private const val TAG = "MicroGInstaller"
private const val STABLE_PACKAGE = "nl.neerdael.milkbeat"

/**
 * Installs MicroG-RE-TV for account sign-in: the latest release's APK, downloaded while the sign-in
 * screen is open and handed to the platform installer only if it is MicroG-RE-TV's package. Android
 * asks the listener to confirm, and refuses an update signed with another key.
 */
@Singleton
class MicroGInstaller
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val client: OkHttpClient,
    ) {
        private val _failed = MutableStateFlow(false)

        /** True once the installer reported a failure, until the next attempt. */
        val failed: StateFlow<Boolean> = _failed.asStateFlow()

        private val _installed = MutableStateFlow(0)

        /** Counts installs the installer reported done, so a screen still waiting moves on. */
        val installed: StateFlow<Int> = _installed.asStateFlow()

        // The sign-in screens waiting for an install, and the committed session they wait for; its confirmation opens
        // only while one does. When the last one leaves a new generation starts, so the install it started can no
        // longer register its session, and the download under way stops.
        private val lock = Any()
        private val waiters = mutableSetOf<Any>()
        private var generation = 0L
        private var awaited: Int? = null

        // The install under way, which a second request joins instead of downloading over its file and session. It
        // runs here rather than in a screen's scope, so one screen leaving does not end it for another still waiting.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var running: Deferred<Boolean>? = null

        // MicroG-RE-TV serves only the stable app; Preview and debug builds could install it and still get no token.
        val isAvailable: Boolean = context.packageName == STABLE_PACKAGE

        fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

        fun installPermissionIntent(): Intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

        /**
         * Downloads and commits the install for [waiter], a sign-in screen that waits for it until [release]; false
         * when the download is not MicroG-RE-TV or fails.
         */
        suspend fun install(waiter: Any): Boolean {
            val install =
                synchronized(lock) {
                    waiters += waiter
                    running?.takeIf { it.isActive } ?: generation.let { attempt -> scope.async { download(attempt) } }.also { running = it }
                }
            return install.await()
        }

        private suspend fun download(attempt: Long): Boolean =
            withContext(Dispatchers.IO) {
                _failed.value = false
                val apk = File(context.cacheDir, "microg/microg-re-tv.apk")
                try {
                    fetch(apk)
                    val archive = context.packageManager.getPackageArchiveInfo(apk.path, 0)
                    if (archive?.packageName != MICROG_PACKAGE) return@withContext false
                    // A sign-in screen that moved on while the download ran opens no installer.
                    ensureActive()
                    commit(apk, attempt)
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Download, archive and installer failures alike leave the offer to try again.
                    Log.w(TAG, "MicroG-RE-TV install failed", e)
                    false
                } finally {
                    apk.delete()
                }
            }

        /** [waiter] moved on. Once no screen waits, a session committed earlier opens no confirmation over what came next. */
        fun release(waiter: Any) =
            synchronized(lock) {
                if (waiters.remove(waiter) && waiters.isEmpty()) {
                    generation++
                    awaited = null
                    running?.cancel()
                    running = null
                }
            }

        internal fun awaits(sessionId: Int): Boolean = synchronized(lock) { sessionId != -1 && awaited == sessionId }

        internal fun reportFailure() {
            synchronized(lock) { awaited = null }
            _failed.value = true
        }

        internal fun reportInstalled() {
            synchronized(lock) { awaited = null }
            _installed.value++
        }

        private suspend fun fetch(target: File) {
            target.parentFile?.mkdirs()
            client.newCall(Request.Builder().url(MICROG_APK_URL).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("MicroG-RE-TV download failed: HTTP ${response.code}")
                val body = response.body
                if (body.contentLength() > MAX_APK_BYTES) throw IOException("MicroG-RE-TV download is too large")
                body.byteStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_APK_BYTES) throw IOException("MicroG-RE-TV download is too large")
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }

        private fun commit(
            apk: File,
            attempt: Long,
        ) {
            val installer = context.packageManager.packageInstaller
            val params =
                PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(MICROG_PACKAGE)
                    setSize(apk.length())
                }
            val sessionId = installer.createSession(params)
            try {
                installer.openSession(sessionId).use { session ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        apk.inputStream().use { it.copyTo(output) }
                        session.fsync(output)
                    }
                    val callback =
                        PendingIntent.getBroadcast(
                            context,
                            INSTALL_REQUEST,
                            Intent(context, MicroGInstallReceiver::class.java),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                        )
                    val current =
                        synchronized(lock) {
                            (generation == attempt).also { if (it) awaited = sessionId }
                        }
                    check(current) { "The sign-in left before its install was committed" }
                    session.commit(callback.intentSender)
                }
            } catch (e: Exception) {
                // An uncommitted session keeps its staged APK until abandoned.
                installer.abandonSession(sessionId)
                throw e
            }
        }
    }
