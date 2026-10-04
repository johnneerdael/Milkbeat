package io.github.aedev.flow

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.network.AppProxyManager
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.utils.AppLanguageManager
import io.github.aedev.flow.utils.FlowCrashHandler
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.security.Security
import javax.inject.Inject

@HiltAndroidApp
class FlowApplication :
    Application(),
    SingletonImageLoader.Factory {
    @Inject
    lateinit var imageLoader: ImageLoader

    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var downloadUtil: dagger.Lazy<io.github.aedev.flow.data.download.DownloadUtil>

    @Inject
    lateinit var mirrorJobs: dagger.Lazy<io.github.aedev.flow.plugin.mirror.PlaylistMirrorJobs>

    @Inject
    lateinit var libraryScans: dagger.Lazy<io.github.aedev.flow.data.library.index.LibraryScanJobs>

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    /**
     * The last session's track is restored paused at start; resolving its stream now means pressing
     * play starts it without waiting on the audio plugin.
     */
    private suspend fun warmRestoredTrack() {
        val track =
            withTimeoutOrNull(RESTORED_TRACK_WAIT_MS) {
                io.github.aedev.flow.player.EnhancedMusicPlayerManager.currentTrack
                    .filterNotNull()
                    .first()
            } ?: return
        if (io.github.aedev.flow.data.localmedia.LocalMediaIds
                .isLocal(track.videoId)
        ) {
            return
        }
        runCatching {
            downloadUtil.get().prefetch(
                io.github.aedev.flow.player.EnhancedMusicPlayerManager
                    .streamUri(track),
            )
        }.onSuccess { Log.d(TAG, "Restored track ${track.videoId} ready to play") }
            .onFailure { Log.w(TAG, "Restored track ${track.videoId} not resolved: ${it.message}") }
    }

    companion object {
        private const val TAG = "FlowApplication"
        private const val SUBSCRIPTION_CHECK_WORK = "subscription_check_work_v2"
        private const val LEGACY_SUBSCRIPTION_CHECK_WORK = "subscription_check_work"
        private const val RESTORED_TRACK_WAIT_MS = 20_000L
        private const val LIBRARY_SCAN_CHECK_DELAY_MS = 15_000L
        lateinit var appContext: Context
            private set
    }

    override fun attachBaseContext(base: Context) {
        val selectedLanguage = AppLanguageManager.loadSelectedLanguageTag(base)
        super.attachBaseContext(AppLanguageManager.wrapContext(base, selectedLanguage))
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { mirrorJobs.get().start(this) }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // After the first screens are up, so opening the index never competes with cold start.
            delay(LIBRARY_SCAN_CHECK_DELAY_MS)
            runCatching { libraryScans.get().scanIfStale() }.onFailure { Log.w(TAG, "Library scan check failed", it) }
        }
        val playerPreferences = PlayerPreferences(this)

        // Injects modern TLS/SSL certificates so OkHttp and Ktor don't crash
        if (android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.N_MR1) {
            Security.insertProviderAt(Conscrypt.newProvider(), 1)
        }

        // Install crash handler for real-time monitoring
        FlowCrashHandler.install(this)

        NotificationHelper.createNotificationChannels(this)

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            // WorkManager keeps periodic work across updates; the worker these name no longer exists.
            WorkManager.getInstance(this@FlowApplication).apply {
                cancelUniqueWork(SUBSCRIPTION_CHECK_WORK)
                cancelUniqueWork(LEGACY_SUBSCRIPTION_CHECK_WORK)
            }

            // A TV checks for updates while it is open; periodic checks an earlier phone build scheduled are cancelled.
            if (BuildConfig.UPDATER_ENABLED) {
                io.github.aedev.flow.notification.UpdateCheckWorker
                    .cancelScheduledChecks(this@FlowApplication)
            }
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            playerPreferences.proxyConfig.collectLatest(AppProxyManager::update)
        }

        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            warmRestoredTrack()
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        // Clean up performance dispatcher resources
        PerformanceDispatcher.shutdown()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        FlowCrashHandler.recordPhase("memory", "FlowApplication.onLowMemory")
        releaseVolatileMemory()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FlowCrashHandler.recordPhase("memory", "FlowApplication.onTrimMemory level=$level")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            releaseVolatileMemory()
        }
    }

    private fun releaseVolatileMemory() {
        if (::imageLoader.isInitialized) {
            imageLoader.memoryCache?.clear()
        }
        if (::okHttpClient.isInitialized) {
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                okHttpClient.connectionPool.evictAll()
            }
        }
    }
}
