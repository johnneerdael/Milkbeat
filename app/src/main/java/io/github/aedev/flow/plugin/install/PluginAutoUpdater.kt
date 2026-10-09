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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Where updating the plugins stands: not asked, running, what the last run installed and left among [Checked.checked], or failed. */
sealed interface PluginUpdatesState {
    data object Idle : PluginUpdatesState

    data object Checking : PluginUpdatesState

    /**
     * [updates] wait for the listener's review (new permissions or a browser check); [failed] could not
     * be downloaded or installed and are tried again next run; [installed] went in by themselves;
     * [requiresAppUpdate] need a newer Milkbeat and wait, undownloaded, until the app is updated.
     */
    data class Checked(
        val updates: List<PluginUpdate>,
        val checked: Set<String>,
        val installed: List<PluginUpdate> = emptyList(),
        val failed: List<PluginUpdate> = emptyList(),
        val requiresAppUpdate: List<PluginUpdate> = emptyList(),
    ) : PluginUpdatesState

    data class Failed(
        val messageResource: Int?,
    ) : PluginUpdatesState
}

/** What a background run did that the listener has not been told about yet. */
data class PluginUpdateReport(
    val installed: List<PluginUpdate>,
    val needsReview: List<PluginUpdate>,
    val requiresAppUpdate: List<PluginUpdate> = emptyList(),
)

private enum class UpdateOutcome { INSTALLED, NEEDS_REVIEW, REQUIRES_APP_UPDATE, FAILED }

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

        /** Updates the listener has seen waiting for review, so each is announced once while the app process lives. */
        private val seenReviews = ConcurrentHashMap.newKeySet<String>()

        /** Updates that need the listener's review; their download is not fetched again while the app process lives. */
        private val heldForReview = ConcurrentHashMap.newKeySet<String>()

        /** Updates that need a newer Milkbeat, announced once while the app process lives; updating the app starts a new one. */
        private val seenAppUpdates = ConcurrentHashMap.newKeySet<String>()

        /** Downloads the package check refused as too new for this Milkbeat; they are not fetched again while the app process lives. */
        private val heldForAppUpdate = ConcurrentHashMap.newKeySet<String>()

        /** Elapsed-realtime of this process's last background run; null until the launch run. */
        private var lastCheckMs: Long? = null

        val state: Flow<PluginUpdatesState> = combine(runState, registry.state) { run, plugins -> run.forInstalled(plugins.plugins) }

        val report: StateFlow<PluginUpdateReport?> = unreported.asStateFlow()

        val enabled: Flow<Boolean> = dataManager.automaticPluginUpdates

        suspend fun setEnabled(enabled: Boolean) = dataManager.setAutomaticPluginUpdates(enabled)

        /** Takes what [shown] told the listener out of the report; anything a later run added stays. */
        fun markReported(shown: PluginUpdateReport) {
            unreported.update { current -> current?.without(shown) }
        }

        /** Checks every installed plugin and installs what it can; a request during a run gets that run's result. */
        suspend fun updateAll(): PluginUpdatesState = update(background = false)

        /** Runs for as long as the caller keeps it running, i.e. while the app is in the foreground. */
        suspend fun checkWhileForeground() {
            while (true) {
                delay(AutoUpdateSchedule.delayUntilNextCheck(lastCheckMs, SystemClock.elapsedRealtime()))
                lastCheckMs = SystemClock.elapsedRealtime()
                if (enabled.first() &&
                    registry.state.value.plugins
                        .isNotEmpty()
                ) {
                    update(background = true)
                }
            }
        }

        /** Only a run that actually ran in the background is reported; one the listener started shows in Settings. */
        private suspend fun update(background: Boolean): PluginUpdatesState {
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
            val checked = result as? PluginUpdatesState.Checked ?: return result
            val newReviews = checked.updates.filter { seenReviews.add(it.key()) }
            val newAppUpdates = checked.requiresAppUpdate.filter { seenAppUpdates.add(it.key()) }
            if (background && (checked.installed.isNotEmpty() || newReviews.isNotEmpty() || newAppUpdates.isNotEmpty())) {
                unreported.update { earlier ->
                    PluginUpdateReport(
                        installed = earlier?.installed.orEmpty() + checked.installed,
                        needsReview = earlier?.needsReview.orEmpty() + newReviews,
                        requiresAppUpdate = earlier?.requiresAppUpdate.orEmpty() + newAppUpdates,
                    )
                }
            }
            return result
        }

        private suspend fun run(): PluginUpdatesState {
            val plugins = registry.state.value.plugins
            val updates =
                try {
                    checker.check(plugins)
                } catch (e: PluginInstallException) {
                    return PluginUpdatesState.Failed(e.messageResource)
                }
            val outcomes = updates.installable.associateWith { install(it) }

            fun withOutcome(outcome: UpdateOutcome) = outcomes.filterValues { it == outcome }.keys.toList()
            return PluginUpdatesState.Checked(
                updates = withOutcome(UpdateOutcome.NEEDS_REVIEW),
                checked = plugins.mapTo(HashSet()) { it.id },
                installed = withOutcome(UpdateOutcome.INSTALLED),
                failed = withOutcome(UpdateOutcome.FAILED),
                requiresAppUpdate = updates.requiresAppUpdate + withOutcome(UpdateOutcome.REQUIRES_APP_UPDATE),
            )
        }

        private suspend fun install(update: PluginUpdate): UpdateOutcome {
            val key = update.key()
            if (key in heldForReview) return UpdateOutcome.NEEDS_REVIEW
            if (key in heldForAppUpdate) return UpdateOutcome.REQUIRES_APP_UPDATE
            return try {
                val pending = installer.fetch(update.url, update)
                if (pending.needsConsent) {
                    heldForReview += key
                    UpdateOutcome.NEEDS_REVIEW
                } else {
                    // The registry writes its file before its state, so a run cancelled by leaving the app must not stop between them.
                    val installed = withContext(NonCancellable) { installer.install(pending) }
                    accounts.replaced(installed.id)
                    if (installed.manifest.signIn.isNotEmpty()) runCatching { accounts.refresh(installed.id) }
                    UpdateOutcome.INSTALLED
                }
            } catch (_: BrowserVerificationRequiredException) {
                heldForReview += key
                UpdateOutcome.NEEDS_REVIEW
            } catch (_: PluginRequiresAppUpdateException) {
                // A list that does not state the release's minimum API yet; the package check is the authority.
                heldForAppUpdate += key
                UpdateOutcome.REQUIRES_APP_UPDATE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                UpdateOutcome.FAILED
            }
        }
    }

private fun PluginUpdate.key(): String = "$pluginId:$versionCode"

private fun PluginUpdateReport.without(shown: PluginUpdateReport): PluginUpdateReport? {
    val rest =
        PluginUpdateReport(
            installed - shown.installed.toSet(),
            needsReview - shown.needsReview.toSet(),
            requiresAppUpdate - shown.requiresAppUpdate.toSet(),
        )
    return rest.takeIf { it.installed.isNotEmpty() || it.needsReview.isNotEmpty() || it.requiresAppUpdate.isNotEmpty() }
}

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

            fun stillNewer(update: PluginUpdate) = versions[update.pluginId]?.let { it < update.versionCode } == true
            copy(
                updates = updates.filter(::stillNewer),
                failed = failed.filter(::stillNewer),
                requiresAppUpdate = requiresAppUpdate.filter(::stillNewer),
            )
        }
    }
