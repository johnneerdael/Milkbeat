package io.github.aedev.flow.plugin.install

import android.os.SystemClock
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.data.update.AutoUpdateSchedule
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Where updating the plugins stands: not asked, running, what the last run installed and left among [Checked.checked], or failed. */
sealed interface PluginUpdatesState {
    data object Idle : PluginUpdatesState

    data object Checking : PluginUpdatesState

    /** [updates] are still to install, each needing the listener's review; [installed] went in by themselves. */
    data class Checked(
        val updates: List<PluginUpdate>,
        val checked: Set<String>,
        val installed: List<PluginUpdate> = emptyList(),
    ) : PluginUpdatesState

    data class Failed(
        val messageResource: Int?,
    ) : PluginUpdatesState
}

/** What a background run did that the listener has not been told about yet. */
data class PluginUpdateReport(
    val installed: List<PluginUpdate>,
    val needsReview: List<PluginUpdate>,
)

/**
 * Keeps the installed plugins current: finds newer versions and installs each one that verifies and
 * asks for nothing the listener has not already granted. An update that wants more, or whose download
 * needs a browser check, stays offered for review in Settings, Plugins. Runs on request, and shortly
 * after launch and every six hours while the app is open when automatic plugin updates are on.
 */
@Singleton
class PluginAutoUpdater
    @Inject
    constructor(
        private val registry: PluginRegistry,
        private val checker: PluginUpdateChecker,
        private val installer: PluginInstaller,
        private val accounts: PluginAccounts,
        private val dataManager: LocalDataManager,
    ) {
        private val runState = MutableStateFlow<PluginUpdatesState>(PluginUpdatesState.Idle)
        private val unreported = MutableStateFlow<PluginUpdateReport?>(null)

        /** Updates already reported as needing review, so each is reported once while the app process lives. */
        private val reportedReviews = HashSet<String>()

        /** Updates that need the listener's review; their download is not fetched again while the app process lives. */
        private val heldForReview = HashSet<String>()

        /** Elapsed-realtime of this process's last background run; null until the launch run. */
        private var lastCheckMs: Long? = null

        val state: Flow<PluginUpdatesState> = combine(runState, registry.state) { run, plugins -> run.forInstalled(plugins.plugins) }

        val report: StateFlow<PluginUpdateReport?> = unreported.asStateFlow()

        val enabled: Flow<Boolean> = dataManager.automaticPluginUpdates

        suspend fun setEnabled(enabled: Boolean) = dataManager.setAutomaticPluginUpdates(enabled)

        fun markReported(report: PluginUpdateReport) {
            unreported.compareAndSet(report, null)
        }

        /** Checks every installed plugin and installs what it can; a request during a run gets that run's result. */
        suspend fun updateAll(): PluginUpdatesState {
            val previous = runState.value
            if (previous == PluginUpdatesState.Checking || !runState.compareAndSet(previous, PluginUpdatesState.Checking)) {
                return runState.first { it != PluginUpdatesState.Checking }
            }
            var result: PluginUpdatesState = PluginUpdatesState.Idle
            try {
                result = run()
            } finally {
                runState.value = result
            }
            return result
        }

        /** Runs for as long as the caller keeps it running, i.e. while the app is in the foreground. */
        suspend fun checkWhileForeground() {
            while (true) {
                delay(AutoUpdateSchedule.delayUntilNextCheck(lastCheckMs, SystemClock.elapsedRealtime()))
                lastCheckMs = SystemClock.elapsedRealtime()
                if (enabled.first() &&
                    registry.state.value.plugins
                        .isNotEmpty()
                ) {
                    report(updateAll())
                }
            }
        }

        private suspend fun run(): PluginUpdatesState {
            val plugins = registry.state.value.plugins
            val updates =
                try {
                    checker.check(plugins)
                } catch (e: PluginInstallException) {
                    return PluginUpdatesState.Failed(e.messageResource)
                }
            val (installed, left) = updates.partition { install(it) }
            return PluginUpdatesState.Checked(left, plugins.mapTo(HashSet()) { it.id }, installed)
        }

        /** False leaves [update] offered: it needs consent, a browser check, or its download failed and is tried again next run. */
        private suspend fun install(update: PluginUpdate): Boolean {
            val key = update.key()
            if (key in heldForReview) return false
            return try {
                val pending = installer.fetch(update.url, update)
                if (pending.needsConsent) {
                    heldForReview += key
                    false
                } else {
                    // The registry writes its file before its state, so a run cancelled by leaving the app must not stop between them.
                    val installed = withContext(NonCancellable) { installer.install(pending) }
                    if (installed.manifest.signIn.isNotEmpty()) runCatching { accounts.refresh(installed.id) }
                    true
                }
            } catch (_: BrowserVerificationRequiredException) {
                heldForReview += key
                false
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }

        private fun report(result: PluginUpdatesState) {
            val checked = result as? PluginUpdatesState.Checked ?: return
            val newReviews = checked.updates.filter { reportedReviews.add(it.key()) }
            if (checked.installed.isEmpty() && newReviews.isEmpty()) return
            unreported.update { earlier ->
                PluginUpdateReport(
                    installed = earlier?.installed.orEmpty() + checked.installed,
                    needsReview = earlier?.needsReview.orEmpty() + newReviews,
                )
            }
        }
    }

private fun PluginUpdate.key(): String = "$pluginId:$versionCode"

/**
 * The run's findings for the plugins [installed] now: a plugin added since was not checked, so the
 * findings no longer hold; an update stays offered only while its plugin is installed and older.
 */
internal fun PluginUpdatesState.forInstalled(installed: List<InstalledPlugin>): PluginUpdatesState =
    when {
        this !is PluginUpdatesState.Checked -> {
            this
        }

        installed.any { it.id !in checked } -> {
            PluginUpdatesState.Idle
        }

        else -> {
            val versions = installed.associate { it.id to it.manifest.versionCode }
            copy(updates = updates.filter { update -> versions[update.pluginId]?.let { it < update.versionCode } == true })
        }
    }
