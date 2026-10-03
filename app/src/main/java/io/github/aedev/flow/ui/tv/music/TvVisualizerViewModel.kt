package io.github.aedev.flow.ui.tv.music

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.local.NowPlayingView
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.player.audio.visualizer.VisualizerAudioTap
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import io.github.aedev.flow.player.audio.visualizer.VisualizerSettings
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.neerdael.projectm.core.ProjectMJNI
import javax.inject.Inject

/** The now-playing visualizer: whether it shows, the engine behind it, and the audio it hears. */
@HiltViewModel
class TvVisualizerViewModel
    @Inject
    constructor(
        val engine: VisualizerEngine,
        private val tap: VisualizerAudioTap,
        private val preferences: VisualizerPreferences,
    ) : ViewModel() {
        val active: StateFlow<Boolean> = engine.active.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /**
         * The stored settings; null until read, so the visualizer never starts on defaults and then reloads.
         * The last value is dropped once no visualizer shows: this view model outlives now-playing, and a
         * reopened visualizer must not start on what the settings said before they were changed.
         */
        val settings: StateFlow<VisualizerSettings?> =
            engine.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000, replayExpirationMillis = 0), null)

        val timingOffsetMs: StateFlow<Int> =
            preferences.timingOffsetMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

        /** The view now-playing shows, as its view button last left it; null until read. */
        val nowPlayingView: StateFlow<NowPlayingView?> =
            preferences.nowPlayingView.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

        fun setNowPlayingView(view: NowPlayingView) {
            viewModelScope.launch { preferences.setNowPlayingView(view) }
        }

        val diagnosticsShown: StateFlow<Boolean> =
            preferences.diagnostics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** The engine's recent input level (RMS, 0..1); 0 when no audio arrived in the last second. */
        fun audioLevel(): Float = ProjectMJNI.getAudioLevel()

        /** A reading for the diagnostics line; null until the visualizer has rendered for a second. */
        internal fun diagnostics(): VisualizerDiagnostics? {
            val stats = engine.renderStats ?: return null
            return VisualizerDiagnostics(
                fps = stats.fps,
                targetFps = stats.targetFps,
                width = stats.width,
                height = stats.height,
                autoResolution = stats.autoResolution,
                lightweightTransition = ProjectMJNI.isLightweightTransition(),
                blendPercent = ProjectMJNI.getBlendScalePercent(),
                preset = presetDisplayName(ProjectMJNI.getCurrentPresetName()),
                audioLevel = ProjectMJNI.getAudioLevel(),
            )
        }

        fun startListening() = tap.acquire()

        fun stopListening() = tap.release()

        /** Called on the render thread every frame: the samples heard [leadUs] from now. */
        fun readAudible(
            out: ShortArray,
            leadUs: Long,
        ): Boolean = tap.readAudible(out, leadUs)

        /** Left steps back through the presets shown, right jumps to a random one, as in ProjectM-TV. */
        fun stepPreset(forward: Boolean) {
            if (forward) ProjectMJNI.randomPreset(true) else ProjectMJNI.previousPreset(true)
        }
    }

/** The preset step a remote key asks for while the controls are hidden: right forward, left back. */
internal fun presetStepFor(keyCode: Int): Boolean? =
    when (keyCode) {
        KeyEvent.KEYCODE_DPAD_RIGHT -> true
        KeyEvent.KEYCODE_DPAD_LEFT -> false
        else -> null
    }
