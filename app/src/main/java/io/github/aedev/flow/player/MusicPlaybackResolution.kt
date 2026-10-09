package io.github.aedev.flow.player

import io.github.aedev.flow.plugin.playback.MusicResolutionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal val musicResolutionStatusState = MutableStateFlow<MusicResolutionStatus?>(null)
private val readOnlyResolutionStatus = musicResolutionStatusState.asStateFlow()
val EnhancedMusicPlayerManager.resolutionStatus get() = readOnlyResolutionStatus
