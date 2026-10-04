package io.github.aedev.flow.ui.tv.screens.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.aedev.flow.data.account.AccountPlayHistory
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.install.PendingInstall
import io.github.aedev.flow.plugin.install.PluginInstallException
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.install.PluginLinks
import io.github.aedev.flow.plugin.install.PluginUpdate
import io.github.aedev.flow.plugin.install.PluginUpdateChecker
import io.github.aedev.flow.plugin.preload.PlaylistPreloadJobs
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import javax.inject.Inject

/** Where adding a plugin stands: nothing, fetching it, waiting for consent, or failed. */
sealed interface AddPluginState {
    data object Idle : AddPluginState

    data object Fetching : AddPluginState

    data class Consent(
        val pending: PendingInstall,
    ) : AddPluginState

    data class Failed(
        val message: String,
        val messageResource: Int? = null,
    ) : AddPluginState
}

/** Where checking for plugin updates stands: not asked, checking, the updates found among [Checked.checked], or failed. */
sealed interface PluginUpdatesState {
    data object Idle : PluginUpdatesState

    data object Checking : PluginUpdatesState

    data class Checked(
        val updates: List<PluginUpdate>,
        val checked: Set<String>,
    ) : PluginUpdatesState

    data class Failed(
        val messageResource: Int?,
    ) : PluginUpdatesState
}

data class TvPluginsState(
    val plugins: List<InstalledPlugin> = emptyList(),
    val selection: ProviderSelection = ProviderSelection(),
    val accounts: Map<String, ProviderAccount> = emptyMap(),
    val adding: AddPluginState = AddPluginState.Idle,
    val mirrorPairs: Set<String> = emptySet(),
    val updates: PluginUpdatesState = PluginUpdatesState.Idle,
)

/** Settings, Plugins: what is installed, which plugin provides what, adding, signing in and removing. */
@HiltViewModel
class TvPluginsViewModel
    @Inject
    constructor(
        private val registry: PluginRegistry,
        private val installer: PluginInstaller,
        private val updateChecker: PluginUpdateChecker,
        private val accounts: PluginAccounts,
        private val playHistory: AccountPlayHistory,
        links: PluginLinks,
        val preloadJobs: PlaylistPreloadJobs,
        private val savedState: SavedStateHandle,
        val mirrors: io.github.aedev.flow.plugin.mirror.PlaylistMirrorCoordinator,
    ) : ViewModel() {
        private val adding = MutableStateFlow<AddPluginState>(AddPluginState.Idle)
        private var fetchJob: Job? = null
        private val updates = MutableStateFlow<PluginUpdatesState>(PluginUpdatesState.Idle)
        private var updateCheckJob: Job? = null

        val state: StateFlow<TvPluginsState> =
            combine(
                registry.state,
                accounts.accounts,
                adding,
                mirrors.store.enabledPairs,
                updates,
            ) { registryState, known, add, pairs, found ->
                TvPluginsState(registryState.plugins, registryState.selection, known, add, pairs, found.forInstalled(registryState.plugins))
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TvPluginsState())

        init {
            viewModelScope.launch { links.pending.filterNotNull().collect { url -> fetch(url, links) } }
            viewModelScope.launch {
                registry.state.value.plugins
                    .filter { it.manifest.signIn.isNotEmpty() }
                    .forEach { runCatching { accounts.refresh(it.id) } }
            }
        }

        fun fetch(url: String) = fetch(url, links = null)

        /** Asks the plugin publisher for newer versions of the installed plugins. */
        fun checkForUpdates() {
            updateCheckJob?.cancel()
            updates.value = PluginUpdatesState.Checking
            val installed = registry.state.value.plugins
            updateCheckJob =
                viewModelScope.launch {
                    updates.value =
                        try {
                            PluginUpdatesState.Checked(updateChecker.check(installed), installed.mapTo(HashSet()) { it.id })
                        } catch (e: PluginInstallException) {
                            PluginUpdatesState.Failed(e.messageResource)
                        }
                }
        }

        /** Downloads [update] for the same review and install as any plugin added by hand. */
        fun update(update: PluginUpdate) = fetch(update.url, links = null, update = update)

        private fun fetch(
            url: String,
            links: PluginLinks?,
            update: PluginUpdate? = null,
        ) {
            links?.consume()
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return
            fetchJob?.cancel()
            adding.value = AddPluginState.Fetching
            fetchJob =
                viewModelScope.launch {
                    adding.value =
                        try {
                            AddPluginState.Consent(installer.fetch(trimmed, update))
                        } catch (e: PluginInstallException) {
                            AddPluginState.Failed(e.message.orEmpty(), e.messageResource)
                        }
                }
        }

        fun install() {
            val consent = adding.value as? AddPluginState.Consent ?: return
            viewModelScope.launch {
                adding.value =
                    try {
                        val installed = installer.install(consent.pending)
                        if (installed.manifest.signIn.isNotEmpty()) runCatching { accounts.refresh(installed.id) }
                        AddPluginState.Idle
                    } catch (e: IllegalStateException) {
                        AddPluginState.Failed(e.message ?: "Could not install the plugin")
                    }
            }
        }

        fun cancelAdd() {
            fetchJob?.cancel()
            fetchJob = null
            adding.value = AddPluginState.Idle
        }

        fun remove(id: String) {
            viewModelScope.launch { registry.remove(id) }
        }

        fun select(selection: ProviderSelection) {
            viewModelScope.launch { registry.select(selection) }
        }

        fun mutateSelection(change: (ProviderSelection) -> ProviderSelection) {
            viewModelScope.launch { registry.updateSelection(change) }
        }

        fun consumeHomeRequest(revision: Int): Boolean {
            if (revision == 0 || savedState.get<Int>("plugin-home-revision") == revision) return false
            savedState["plugin-home-revision"] = revision
            cancelAdd()
            return true
        }

        /** Whether listens and views are added to the signed-in account's history, for plugins that report them. */
        val playHistoryEnabled: StateFlow<Boolean> =
            playHistory.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

        fun setPlayHistoryEnabled(enabled: Boolean) {
            viewModelScope.launch { playHistory.setEnabled(enabled) }
        }

        fun setMirrorEnabled(
            source: String,
            target: String,
            enabled: Boolean,
        ) {
            if (enabled && !mirrors.available(source, target)) return
            viewModelScope.launch { mirrors.store.setEnabled(source, target, enabled) }
        }

        fun signOut(id: String) {
            viewModelScope.launch {
                try {
                    accounts.signOut(id)
                } catch (e: PluginCallException) {
                    adding.value = AddPluginState.Failed(e.error.userMessage ?: e.error.message)
                }
            }
        }
    }

/**
 * The check's findings for the plugins [installed] now: a plugin added since was not checked, so the
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
