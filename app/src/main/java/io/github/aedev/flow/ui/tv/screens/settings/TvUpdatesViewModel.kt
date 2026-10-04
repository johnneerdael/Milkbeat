package io.github.aedev.flow.ui.tv.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.update.AutoUpdater
import io.github.aedev.flow.plugin.install.PluginAutoUpdater
import io.github.aedev.flow.plugin.install.PluginUpdateReport
import io.github.aedev.flow.plugin.install.PluginUpdatesState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Automatic app and plugin updates on TV, shared by the app shell (checks, notices and the Settings
 * badge) and Settings > About. Activity-scoped, so each time the app is opened is one session.
 */
@HiltViewModel
class TvUpdatesViewModel
    @Inject
    constructor(
        private val autoUpdater: AutoUpdater,
        private val pluginUpdater: PluginAutoUpdater,
    ) : ViewModel() {
        val isAvailable: Boolean = autoUpdater.isAvailable

        val automatic: StateFlow<Boolean> = autoUpdater.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

        private val announced = MutableStateFlow<Set<String>>(emptySet())

        /** The version whose download is ready, once per session until it is installed. */
        val readyToInstall: Flow<String> =
            autoUpdater.ready
                .filterNotNull()
                .map { it.version }
                .filter { it !in announced.value }

        fun markAnnounced(version: String) {
            announced.update { it + version }
        }

        /** Settings carries a badge while an update is ready to install or a plugin update waits for review. */
        val needsAttention: StateFlow<Boolean> =
            combine(autoUpdater.ready, pluginUpdater.state) { app, plugins ->
                app != null || (plugins as? PluginUpdatesState.Checked)?.updates?.isNotEmpty() == true
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

        /** What the background plugin updates did that has not been shown yet. */
        val pluginReports: Flow<PluginUpdateReport> = pluginUpdater.report.filterNotNull()

        fun markReported(report: PluginUpdateReport) = pluginUpdater.markReported(report)

        fun setAutomatic(enabled: Boolean) {
            viewModelScope.launch { autoUpdater.setEnabled(enabled) }
        }

        suspend fun checkWhileForeground() = autoUpdater.checkWhileForeground()

        suspend fun checkPluginsWhileForeground() = pluginUpdater.checkWhileForeground()
    }
