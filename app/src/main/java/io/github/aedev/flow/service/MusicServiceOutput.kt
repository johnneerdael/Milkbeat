package io.github.aedev.flow.service

import android.os.SystemClock
import androidx.lifecycle.lifecycleScope
import io.github.aedev.flow.R
import io.github.aedev.flow.player.audio.MusicOutputRecovery

internal fun Media3MusicService.initializeOutputRecovery() {
    outputRecovery =
        MusicOutputRecovery(player, lifecycleScope, audioOutputProbe, SystemClock::elapsedRealtime) { recovering ->
            val message =
                if (recovering) {
                    R.string.music_playback_warning_audio_recovering
                } else {
                    R.string.music_playback_warning_audio_stopped
                }
            notifyMusicWarning(
                getString(message),
            )
        }
}
