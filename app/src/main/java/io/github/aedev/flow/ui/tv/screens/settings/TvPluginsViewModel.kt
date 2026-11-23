package io.github.aedev.flow.ui.tv.screens.settings

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.account.AccountPlayHistory
import io.github.aedev.flow.data.update.AutoUpdater
import io.github.aedev.flow.plugin.catalog.PluginAccounts
import io.github.aedev.flow.plugin.host.PluginConnections
import io.github.aedev.flow.plugin.install.BrowserVerification
import io.github.aedev.flow.plugin.install.BrowserVerificationRequiredException
import io.github.aedev.flow.plugin.install.PendingInstall
import io.github.aedev.flow.plugin.install.PluginAutoUpdater
import io.github.aedev.flow.plugin.install.PluginInstallException
import io.github.aedev.flow.plugin.install.PluginInstaller
import io.github.aedev.flow.plugin.install.PluginLinks
import io.github.aedev.flow.plugin.install.PluginRequiresAppUpdateException
import io.github.aedev.flow.plugin.install.PluginUpdate
import io.github.aedev.flow.plugin.install.PluginUpdatesState
import io.github.aedev.flow.plugin.preload.PlaylistPreloadJobs
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.PluginRegistry
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.runtime.PluginCallException
import io.github.aedev.flow.updater.MicroGInstaller
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.ConnectionManifest
import nl.neerdael.milkbeat.plugin.PluginConnectionRow
import nl.neerdael.milkbeat.plugin.PluginConnectionTestState
import java.io.IOException
import javax.inject.Inject

/** Where adding a plugin stands: nothing, fetching it, waiting on a browser check or for consent, needing a newer app, or failed. */
sealed interface AddPluginState {
    data object Idle : AddPluginState

    data object Fetching : AddPluginState

    data class Verifying(
        val verification: BrowserVerification,
    ) : AddPluginState

    data class Consent(
        val pending: PendingInstall,
    ) : AddPluginState

    data class RequiresAppUpdate(
        val pluginName: String,
    ) : AddPluginState

    data class Failed(
        val message: String,
        val messageResource: Int? = null,
    ) : AddPluginState
}

data class TvPluginsState(
    val plugins: List<InstalledPlugin> = emptyList(),
    val selection: ProviderSelection = ProviderSelection(),
    val accounts: Map<String, ProviderAccount> = emptyMap(),
    val adding: AddPluginState = AddPluginState.Idle,
    val updates: PluginUpdatesState = PluginUpdatesState.Idle,
    val connections: PluginConnectionsState = PluginConnectionsState(),
)

/** Where a plugin's reviewed external connections stand on the TV. */
sealed interface ConnectionsPage {
    data object List : ConnectionsPage

    data class EditUrl(
        val connectionId: String?,
    ) : ConnectionsPage

    data class Review(
        val manifest: ConnectionManifest,
        val connectionId: String?,
        val useAccount: Boolean,
    ) : ConnectionsPage
}

data class PluginConnectionsState(
    val pluginId: String? = null,
    val rows: List<PluginConnectionRow> = emptyList(),
    val page: ConnectionsPage? = null,
    val loaded: Boolean = false,
    val busy: Boolean = false,
    val failure: String? = null,
    val testResult: Pair<String, PluginConnectionTestState>? = null,
)

/** Settings, Plugins: what is installed, which plugin provides what, adding, signing in and removing. */
@HiltViewModel
class TvPluginsViewModel
    @Inject
    constructor(
        private val registry: PluginRegistry,
        private val installer: PluginInstaller,
        private val pluginUpdater: PluginAutoUpdater,
        private val accounts: PluginAccounts,
        private val playHistory: AccountPlayHistory,
        private val pluginConnections: PluginConnections,
        autoUpdater: AutoUpdater,
        links: PluginLinks,
        val preloadJobs: PlaylistPreloadJobs,
        private val savedState: SavedStateHandle,
        microGInstaller: MicroGInstaller,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        /** Whether this build can download and install MicroG-RE-TV for a plugin's Google account sign-in. */
        val microGDownloadable: Boolean = microGInstaller.isAvailable

        private val adding = MutableStateFlow<AddPluginState>(AddPluginState.Idle)
        private val connectionsState = MutableStateFlow(PluginConnectionsState())
        private var connectionsJob: Job? = null

        /** Whether this build installs Milkbeat updates itself, so a plugin needing a newer app can point there. */
        val appUpdatesAvailable: Boolean = autoUpdater.isAvailable
        private var fetchJob: Job? = null

        val state: StateFlow<TvPluginsState> =
            combine(
                registry.state,
                accounts.accounts,
                adding,
                pluginUpdater.state,
                connectionsState,
            ) { registryState, known, add, found, pluginConnections ->
                TvPluginsState(registryState.plugins, registryState.selection, known, add, found, pluginConnections)
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

        /** Checks every installed plugin and installs each update that asks for nothing new. */
        fun updateAll() {
            viewModelScope.launch { pluginUpdater.updateAll() }
        }

        /** Whether plugins also update by themselves shortly after launch and every six hours. */
        val automaticUpdates: StateFlow<Boolean> =
            pluginUpdater.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

        fun setAutomaticUpdates(enabled: Boolean) {
            viewModelScope.launch { pluginUpdater.setEnabled(enabled) }
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
            load { installer.fetch(trimmed, update) }
        }

        /** The browser check passed and Buzzheavier handed [fileUrl] to the page. */
        fun verified(fileUrl: String) {
            val verifying = adding.value as? AddPluginState.Verifying ?: return
            load { installer.fetch(verifying.verification, fileUrl) }
        }

        private fun load(fetch: suspend () -> PendingInstall) {
            fetchJob?.cancel()
            adding.value = AddPluginState.Fetching
            fetchJob =
                viewModelScope.launch {
                    adding.value =
                        try {
                            AddPluginState.Consent(fetch())
                        } catch (e: BrowserVerificationRequiredException) {
                            AddPluginState.Verifying(e.verification)
                        } catch (e: PluginRequiresAppUpdateException) {
                            AddPluginState.RequiresAppUpdate(e.pluginName)
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
                        accounts.replaced(installed.id)
                        if (installed.manifest.signIn.isNotEmpty()) runCatching { accounts.refresh(installed.id) }
                        AddPluginState.Idle
                    } catch (e: PluginInstallException) {
                        AddPluginState.Failed(e.message.orEmpty(), e.messageResource)
                    } catch (e: IllegalStateException) {
                        AddPluginState.Failed(e.message ?: context.getString(R.string.tv_plugins_install_failed))
                    }
            }
        }

        fun cancelAdd() {
            fetchJob?.cancel()
            fetchJob = null
            adding.value = AddPluginState.Idle
        }

        fun remove(id: String) {
            viewModelScope.launch {
                // Revoked first and throughout, so no token or sign-in still running survives the removal.
                try {
                    accounts.removing(id) { registry.remove(id) }
                } catch (e: IOException) {
                    adding.value = AddPluginState.Failed(e.message.orEmpty(), R.string.tv_plugins_storage_failed)
                }
            }
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

        fun signOut(id: String) {
            viewModelScope.launch {
                try {
                    accounts.signOut(id)
                } catch (e: PluginCallException) {
                    adding.value = AddPluginState.Failed(e.error.userMessage ?: e.error.message)
                } catch (e: IOException) {
                    adding.value = AddPluginState.Failed(e.message.orEmpty(), R.string.tv_plugins_storage_failed)
                }
            }
        }

        /** Opens a plugin's reviewed external connections, loading what it has configured. */
        fun openConnections(pluginId: String) {
            connectionsJob?.cancel()
            connectionsState.value = PluginConnectionsState(pluginId = pluginId, page = ConnectionsPage.List)
            loadConnections()
        }

        fun closeConnections() {
            connectionsJob?.cancel()
            connectionsState.value = PluginConnectionsState()
        }

        /** Returns from the editor or review to the configured add-on list. */
        fun showConnectionList() {
            updateConnections { it.copy(page = ConnectionsPage.List, failure = null) }
        }

        fun editConnectionUrl(connectionId: String?) {
            updateConnections { it.copy(page = ConnectionsPage.EditUrl(connectionId), failure = null) }
        }

        /** Reviews a pasted link: the host fetches the manifest itself, before anything is approved. */
        fun reviewConnection(url: String) {
            val pluginId = connectionsState.value.pluginId ?: return
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return
            connectionsJob?.cancel()
            connectionsState.value = connectionsState.value.copy(busy = true, failure = null)
            connectionsJob =
                viewModelScope.launch {
                    val next =
                        try {
                            val manifest = pluginConnections.inspect(trimmed)
                            connectionsState.value.copy(
                                busy = false,
                                page =
                                    ConnectionsPage.Review(
                                        manifest,
                                        connectionsState.value.page
                                            .let { it as? ConnectionsPage.EditUrl }
                                            ?.connectionId,
                                        false,
                                    ),
                            )
                        } catch (e: Exception) {
                            connectionsState.value.copy(
                                busy = false,
                                failure =
                                    e.message ?: context.getString(R.string.tv_plugins_connections_review_failed),
                            )
                        }
                    connectionsState.value = next
                }
        }

        fun setConnectionReviewAccount(useAccount: Boolean) {
            updateConnections { state ->
                val page = state.page as? ConnectionsPage.Review ?: return@updateConnections state
                state.copy(page = page.copy(useAccount = useAccount))
            }
        }

        /** Records the approval and hands the reviewed link to the plugin. */
        fun approveConnection() {
            val pluginId = connectionsState.value.pluginId ?: return
            val plugin =
                registry.state.value.plugins
                    .firstOrNull { it.id == pluginId } ?: return
            val page = connectionsState.value.page as? ConnectionsPage.Review ?: return
            connectionsJob?.cancel()
            connectionsState.value = connectionsState.value.copy(busy = true, failure = null)
            connectionsJob =
                viewModelScope.launch {
                    try {
                        // The reviewed connection id carries through a Replace Link, so the plugin
                        // replaces its row and its approval instead of adding a second one.
                        pluginConnections.approve(plugin, page.manifest, page.useAccount, page.connectionId)
                        loadConnections()
                    } catch (e: Exception) {
                        updateConnections {
                            it.copy(busy = false, failure = e.message ?: context.getString(R.string.tv_plugins_connections_save_failed))
                        }
                    }
                }
        }

        fun setConnectionEnabled(
            id: String,
            enabled: Boolean,
        ) {
            val pluginId = connectionsState.value.pluginId ?: return
            viewModelScope.launch {
                runCatching { pluginConnections.setEnabled(pluginId, id, enabled) }
                loadConnections()
            }
        }

        fun removeConnection(row: PluginConnectionRow) {
            val pluginId = connectionsState.value.pluginId ?: return
            val plugin =
                registry.state.value.plugins
                    .firstOrNull { it.id == pluginId } ?: return
            viewModelScope.launch {
                try {
                    pluginConnections.remove(plugin, row)
                } catch (e: Exception) {
                    updateConnections { it.copy(failure = e.message ?: context.getString(R.string.tv_plugins_connections_remove_failed)) }
                }
                loadConnections()
            }
        }

        fun testConnection(id: String) {
            val pluginId = connectionsState.value.pluginId ?: return
            connectionsJob?.cancel()
            connectionsState.value = connectionsState.value.copy(busy = true, failure = null, testResult = null)
            connectionsJob =
                viewModelScope.launch {
                    val result =
                        runCatching { pluginConnections.test(pluginId, id) }
                            .getOrElse { error ->
                                updateConnections {
                                    it.copy(
                                        busy = false,
                                        failure =
                                            error.message ?: context.getString(R.string.tv_plugins_connections_test_failed),
                                    )
                                }
                                return@launch
                            }
                    updateConnections { it.copy(busy = false, testResult = id to result.state) }
                }
        }

        private fun updateConnections(change: (PluginConnectionsState) -> PluginConnectionsState) {
            connectionsState.value = change(connectionsState.value)
        }

        private fun loadConnections() {
            val pluginId = connectionsState.value.pluginId ?: return
            connectionsJob?.cancel()
            connectionsState.value = connectionsState.value.copy(busy = true, failure = null)
            connectionsJob =
                viewModelScope.launch {
                    val rows =
                        runCatching { pluginConnections.list(pluginId) }.getOrElse { emptyList() }
                    updateConnections { it.copy(rows = rows, busy = false, loaded = true, page = ConnectionsPage.List) }
                }
        }
    }
