package io.github.aedev.flow

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.data.playlist.PlaylistTransfer
import io.github.aedev.flow.notification.NotificationHelper
import io.github.aedev.flow.player.BackgroundPlaybackPolicy
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.LifecyclePlaybackPreferences
import io.github.aedev.flow.player.MemoryPressurePolicy
import io.github.aedev.flow.plugin.install.PluginLinks
import io.github.aedev.flow.ui.components.library.message
import io.github.aedev.flow.ui.screens.crash.CrashReportScreen
import io.github.aedev.flow.ui.startup.FlowTheme
import io.github.aedev.flow.ui.startup.SplashController
import io.github.aedev.flow.ui.startup.SplashTone
import io.github.aedev.flow.ui.startup.ThemeSettings
import io.github.aedev.flow.ui.startup.themeSettings
import io.github.aedev.flow.ui.tv.FlowTvApp
import io.github.aedev.flow.utils.AppLanguageManager
import io.github.aedev.flow.utils.FlowCrashHandler
import io.github.aedev.flow.utils.PLAYLIST_FILE_MIME_TYPE
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val deeplinkVideoIdState = mutableStateOf<String?>(null)
    val deeplinkVideoId: State<String?> = deeplinkVideoIdState

    @Inject
    lateinit var lifecyclePlaybackPreferences: LifecyclePlaybackPreferences

    @Inject
    lateinit var playlistTransfer: dagger.Lazy<PlaylistTransfer>

    @Inject
    lateinit var pluginLinks: PluginLinks

    // A recreated activity gets its launch intent again; a playlist file in it was already imported.
    private var isRestoringState = false

    private val splashController = SplashController(this)

    private fun videoPlaybackStateName(state: Int?): String =
        when (state) {
            androidx.media3.common.Player.STATE_IDLE -> "IDLE"
            androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
            androidx.media3.common.Player.STATE_READY -> "READY"
            androidx.media3.common.Player.STATE_ENDED -> "ENDED"
            null -> "NO_PLAYER"
            else -> "UNKNOWN($state)"
        }

    private fun lifecyclePlaybackSnapshot(): String {
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val playerManager = EnhancedPlayerManager.getInstance()
        val playerState = playerManager.playerState.value
        val player = playerManager.getPlayer()
        return "interactive=${powerManager?.isInteractive} lifecycle=${lifecycle.currentState} " +
            "bgPref=${lifecyclePlaybackPreferences.settings.backgroundPlayEnabled} " +
            "explicitBg=${GlobalPlayerState.isExplicitBackgroundPlaybackActive.value} " +
            "video=${playerState.currentVideoId} exo=${videoPlaybackStateName(player?.playbackState)} " +
            "pwr=${player?.playWhenReady} playing=${player?.isPlaying} buffering=${playerState.isBuffering} " +
            "pos=${player?.currentPosition}/${player?.duration} idx=${player?.currentMediaItemIndex} count=${player?.mediaItemCount}"
    }

    private fun videoLifecycleLog(message: String) {
        Log.w("FlowVideoLifecycle", "$message | ${lifecyclePlaybackSnapshot()}")
    }

    override fun attachBaseContext(newBase: Context) {
        val selectedLanguage = AppLanguageManager.loadSelectedLanguageTag(newBase)
        super.attachBaseContext(AppLanguageManager.wrapContext(newBase, selectedLanguage))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        splashController.install(installSplashScreen())

        super.onCreate(savedInstanceState)

        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        enableEdgeToEdge(
            statusBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
            navigationBarStyle =
                SystemBarStyle.auto(
                    android.graphics.Color.TRANSPARENT,
                    android.graphics.Color.TRANSPARENT,
                ),
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }

        // Player setup reads DataStore and opens the media cache index, so it runs off the main
        // thread and settles after the first frame instead of blocking onCreate.
        lifecycleScope.launch { GlobalPlayerState.initializeAsync(applicationContext) }

        // Snapshot lives in the process, not the activity: onStop reads it synchronously and must
        // still see it after a recreation it runs inside of (#817).
        lifecyclePlaybackPreferences.observeIn(lifecycleScope)

        val dataManager = LocalDataManager(applicationContext)

        isRestoringState = savedInstanceState != null
        handleIntent(intent)
        isRestoringState = false

        // Read now, alongside the rest of startup, so the theme is usually known by the first composition.
        val storedTheme = MutableStateFlow<ThemeSettings?>(null)
        lifecycleScope.launch { dataManager.themeSettings().collect { storedTheme.value = it } }

        setContent {
            // Nothing is composed until the theme is known: the splash covers the wait, and the app
            // composes once in the right theme instead of twice.
            val theme = storedTheme.collectAsState().value ?: return@setContent

            var pendingCrashLog by remember {
                mutableStateOf(FlowCrashHandler.getLastCrash(applicationContext))
            }

            if (pendingCrashLog != null) {
                SideEffect { splashController.contentReady = true }
                FlowTheme(theme) {
                    CrashReportScreen(
                        report = pendingCrashLog!!,
                        onContinue = {
                            FlowCrashHandler.clearLastCrash(applicationContext)
                            pendingCrashLog = null
                        },
                    )
                }
                return@setContent
            }

            FlowTheme(theme) {
                LaunchedEffect(Unit) { splashController.rememberTheme(SplashTone.BLACK) }

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .semantics { testTagsAsResourceId = true },
                ) {
                    val deeplinkVideoId by this@MainActivity.deeplinkVideoId
                    SideEffect { splashController.contentReady = true }
                    val pluginLink by pluginLinks.pending.collectAsStateWithLifecycle()
                    FlowTvApp(
                        deeplinkVideoId = deeplinkVideoId,
                        onDeeplinkConsumed = { consumeDeeplink() },
                        pluginLinkPending = pluginLink != null,
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        FlowCrashHandler.recordPhase("activity", "onResume")
        videoLifecycleLog("onResume")
    }

    override fun onDestroy() {
        videoLifecycleLog("onDestroy")
        val playerManager = EnhancedPlayerManager.getInstance()
        val playerState = playerManager.playerState.value
        val hasActiveVideo =
            playerState.currentVideoId != null &&
                (playerState.playWhenReady || playerState.isPlaying || playerState.isBuffering)
        val shouldKeepBackgroundPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = lifecyclePlaybackPreferences.settings.backgroundPlayEnabled,
                explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
                hasActiveVideo = hasActiveVideo,
            )

        if (shouldKeepBackgroundPlayback) {
            handOffVideoPlaybackToBackground()
        } else if (!isChangingConfigurations) {
            GlobalPlayerState.release()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun playlistFileIn(intent: Intent): Uri? {
        val type = intent.type ?: intent.data?.takeIf { it.scheme == ContentResolver.SCHEME_CONTENT }?.let(contentResolver::getType)
        if (type != PLAYLIST_FILE_MIME_TYPE) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        }
    }

    private fun importPlaylistFile(file: Uri) {
        lifecycleScope.launch {
            val result = playlistTransfer.get().import(file, getString(R.string.imported_playlist_default_name))
            Toast.makeText(this@MainActivity, result.message(this@MainActivity), Toast.LENGTH_LONG).show()
        }
    }

    private fun handleIntent(intent: Intent) {
        if (pluginLinks.offer(intent.data)) return
        if (intent.getBooleanExtra(NotificationHelper.EXTRA_OPEN_UPDATE, false)) {
            intent.removeExtra(NotificationHelper.EXTRA_OPEN_UPDATE)
            return
        }
        val playlistFile = playlistFileIn(intent)
        if (playlistFile != null) {
            if (!isRestoringState) importPlaylistFile(playlistFile)
            return
        }

        if (intent.getBooleanExtra(EXTRA_OPEN_VIDEO_PLAYER, false)) {
            intent.removeExtra(EXTRA_OPEN_VIDEO_PLAYER)
            GlobalPlayerState.currentVideo.value
                ?.id
                ?.let { deeplinkVideoIdState.value = it }
            return
        }

        val videoId = intent.getStringExtra(EXTRA_NOTIFICATION_VIDEO_ID) ?: intent.getStringExtra(EXTRA_VIDEO_ID)
        if (videoId != null) {
            deeplinkVideoIdState.value = videoId
        }
    }

    private fun consumeDeeplink() {
        deeplinkVideoIdState.value = null
    }

    override fun onStop() {
        super.onStop()
        FlowCrashHandler.recordPhase(
            "activity",
            "onStop backgroundPlay=${lifecyclePlaybackPreferences.settings.backgroundPlayEnabled}",
        )
        videoLifecycleLog("onStop")
        handleBackgroundPlaybackOnStop()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        FlowCrashHandler.recordPhase("memory", "MainActivity.onTrimMemory level=$level")
        if (MemoryPressurePolicy.shouldReleaseVideoPlayback(level)) {
            EnhancedPlayerManager.getInstance().handleCriticalMemoryPressure()
        }
    }

    private fun handOffVideoPlaybackToBackground() {
        FlowCrashHandler.recordPhase("background-handoff", "handOffVideoPlaybackToBackground")
        videoLifecycleLog("handOffVideoPlaybackToBackground")
        val playerManager = EnhancedPlayerManager.getInstance()
        val playerState = playerManager.playerState.value
        if (
            playerState.currentVideoId != null &&
            (playerState.playWhenReady || playerState.isPlaying || playerState.isBuffering)
        ) {
            val video = GlobalPlayerState.currentVideo.value
            playerManager.startBackgroundService(
                videoId = video?.id ?: playerState.currentVideoId,
                title = video?.title?.ifEmpty { "Playing..." } ?: "Playing...",
                channel = video?.channelName ?: "",
                thumbnail = video?.thumbnailUrl ?: "",
            )
            playerManager.continueVideoPlaybackInBackground()
        }
    }

    private fun handleBackgroundPlaybackOnStop() {
        FlowCrashHandler.recordPhase("background-handoff", "handleBackgroundPlaybackOnStop")
        videoLifecycleLog("handleBackgroundPlaybackOnStop")
        val playerManager = EnhancedPlayerManager.getInstance()
        val playerState = playerManager.playerState.value
        val hasActiveVideo =
            playerState.currentVideoId != null &&
                (playerState.playWhenReady || playerState.isPlaying || playerState.isBuffering)

        if (!hasActiveVideo) return

        val shouldKeepBackgroundPlayback =
            BackgroundPlaybackPolicy.shouldKeepPlaybackInBackground(
                backgroundPlaybackPreferenceEnabled = lifecyclePlaybackPreferences.settings.backgroundPlayEnabled,
                explicitBackgroundPlaybackActive = GlobalPlayerState.isExplicitBackgroundPlaybackActive.value,
                hasActiveVideo = hasActiveVideo,
            )

        if (shouldKeepBackgroundPlayback) {
            videoLifecycleLog("handleBackgroundPlaybackOnStop handoff")
            handOffVideoPlaybackToBackground()
        } else {
            videoLifecycleLog("handleBackgroundPlaybackOnStop pause")
            playerManager.pause()
            playerManager.stopBackgroundService()
        }
    }

    companion object {
        const val EXTRA_OPEN_VIDEO_PLAYER = "open_video_player"
        const val EXTRA_NOTIFICATION_VIDEO_ID = "notification_video_id"
        const val EXTRA_VIDEO_ID = "video_id"
    }
}
