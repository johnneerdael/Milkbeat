package io.github.aedev.flow.ui.tv.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.AudioQualityReadoutPreferences
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.audio.PlaybackAudioQuality
import io.github.aedev.flow.player.audioQuality
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** The playing music's quality line, while the listener has asked to see it. */
@HiltViewModel
class TvAudioQualityViewModel
    @Inject
    constructor(
        preferences: AudioQualityReadoutPreferences,
    ) : ViewModel() {
        val readout: StateFlow<PlaybackAudioQuality?> =
            combine(preferences.enabled, EnhancedMusicPlayerManager.audioQuality) { shown, quality -> quality.takeIf { shown } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }
