package io.github.aedev.flow.service

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun Media3MusicService.acquireLocks() {
    if (wakeLock?.isHeld != true) {
        wakeLock?.acquire()
    }
    if (wifiLock?.isHeld != true) {
        wifiLock?.acquire()
    }
}

internal fun Media3MusicService.isPlaybackActive(): Boolean {
    if (!playerInitialized) return false
    return player.isPlaying ||
        player.playbackState == Player.STATE_BUFFERING ||
        (
            player.playWhenReady &&
                player.playbackState != Player.STATE_IDLE &&
                player.playbackState != Player.STATE_ENDED
        )
}

internal fun Media3MusicService.updateLocks(isPlaybackActive: Boolean) {
    lockReleaseJob?.cancel()
    lockReleaseJob = null

    if (isPlaybackActive) {
        acquireLocks()
        return
    }

    lockReleaseJob =
        lifecycleScope.launch {
            delay(12_000L)
            if (!isPlaybackActive()) {
                releaseLocks()
                if (!isAppInForeground()) {
                    stopSelf()
                }
            }
        }
}

internal fun Media3MusicService.releaseWakeLock() {
    if (wakeLock?.isHeld == true) {
        wakeLock?.release()
    }
}

internal fun Media3MusicService.releaseWifiLock() {
    if (wifiLock?.isHeld == true) {
        wifiLock?.release()
    }
}

internal fun Media3MusicService.releaseLocks() {
    releaseWakeLock()
    releaseWifiLock()
}

internal fun Media3MusicService.isAppInForeground(): Boolean {
    val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    val runningProcess = activityManager.runningAppProcesses?.firstOrNull { it.pid == Process.myPid() }
    return when (runningProcess?.importance) {
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND,
        ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE,
        -> true

        else -> false
    }
}
