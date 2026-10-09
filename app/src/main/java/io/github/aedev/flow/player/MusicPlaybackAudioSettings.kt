package io.github.aedev.flow.player

import android.util.Log
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import io.github.aedev.flow.data.local.AudioSettingsPersistence
import kotlinx.coroutines.flow.first
import kotlin.math.pow

/** Restore the existing audio settings without capturing a controller across the suspend boundary. */
internal suspend fun restoreMusicAudioSettings(
    persistence: AudioSettingsPersistence?,
    currentPlayer: () -> Player?,
    restoredSpeed: (Float) -> Unit,
) {
    try {
        val settings = persistence?.settingsFlow?.first() ?: return
        Log.d("EnhancedMusicPlayer", "Restoring audio settings: $settings")
        restoredSpeed(settings.speed)
        currentPlayer()?.let { player ->
            val pitch = 2.0.pow(settings.pitch.toDouble() / 12.0).toFloat()
            player.playbackParameters = PlaybackParameters(settings.speed, pitch)
        }
    } catch (error: Exception) {
        Log.e("EnhancedMusicPlayer", "Failed to restore audio settings", error)
    }
}
