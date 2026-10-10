package io.github.aedev.flow.player

import io.github.aedev.flow.player.audio.PlaybackAudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val musicAudioQualityState = MutableStateFlow<PlaybackAudioQuality?>(null)
private val readOnlyAudioQuality = musicAudioQualityState.asStateFlow()

/** What the music player decodes now; null while the playing item's tracks are unknown. */
val EnhancedMusicPlayerManager.audioQuality get() = readOnlyAudioQuality
