package io.github.aedev.flow.ui.tv.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.player.audio.visualizer.VisualizerEngine
import io.github.aedev.flow.player.audio.visualizer.VisualizerSettings
import io.github.aedev.flow.player.audio.visualizer.frameRateOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.neerdael.projectm.core.DisplayInfo
import nl.neerdael.projectm.core.QualityController
import javax.inject.Inject

/** The TV settings' Visualizations category. */
@HiltViewModel
class TvVisualizerSettingsViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val preferences: VisualizerPreferences,
        private val engine: VisualizerEngine,
    ) : ViewModel() {
        val supported = engine.isSupported
        val enabled: StateFlow<Boolean> =
            preferences.enabled.stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                preferences.enabledByDefault,
            )

        val diagnostics: StateFlow<Boolean> = preferences.diagnostics.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        fun setEnabled(enabled: Boolean) {
            viewModelScope.launch { preferences.setEnabled(enabled) }
        }

        val timingOffsetMs: StateFlow<Int> =
            preferences.timingOffsetMs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

        fun setTimingOffsetMs(offsetMs: Int) {
            viewModelScope.launch { preferences.setTimingOffsetMs(offsetMs) }
        }

        fun setDiagnostics(enabled: Boolean) {
            viewModelScope.launch { preferences.setDiagnostics(enabled) }
        }

        private val display: DisplayInfo by lazy { DisplayInfo.detect(context) }

        /**
         * ProjectM-TV's engine settings; null until read, and never read on a device that cannot run the
         * engine. The display is read first, off the main thread: on a TV that loads the native library.
         */
        val settings: StateFlow<VisualizerSettings?> =
            if (supported) {
                flow {
                    withContext(Dispatchers.Default) { display }
                    emitAll(engine.settings)
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
            } else {
                MutableStateFlow(null)
            }

        val defaults: VisualizerSettings get() = engine.defaults

        val frameRates: List<Int> by lazy { frameRateOptions(display.refreshRate) }

        /** The highest height the memory limit allows, 0 when this device needs none. */
        val memoryLimitHeight: Int by lazy {
            val safe = engine.profile.memorySafeHeight()
            if (safe > 0) QualityController.manualHeights(display, safe).last() else 0
        }

        /** Fixed render heights on this panel, with or without the memory limit; Auto comes on top of these. */
        fun renderHeights(memoryLimit: Boolean): List<Int> =
            QualityController.manualHeights(display, if (memoryLimit) engine.profile.memorySafeHeight() else 0).toList()

        /** The saved height as the visualizer will use it: 0 (Auto) when this panel or the memory limit rules it out. */
        fun effectiveRenderHeight(settings: VisualizerSettings): Int =
            QualityController.validFixedHeight(
                display,
                if (settings.memoryLimit) engine.profile.memorySafeHeight() else 0,
                settings.renderHeight,
            )

        fun musicCategories(): List<String> = engine.musicCategories()

        private val skipped = MutableStateFlow(0)
        val skippedPresets: StateFlow<Int> = skipped.asStateFlow()

        fun refreshSkippedPresets() {
            viewModelScope.launch { skipped.value = engine.skippedPresets() }
        }

        fun resetSkippedPresets() {
            viewModelScope.launch {
                engine.resetSkippedPresets()
                skipped.value = 0
            }
        }

        fun update(change: suspend VisualizerPreferences.() -> Unit) {
            viewModelScope.launch { preferences.change() }
        }
    }
