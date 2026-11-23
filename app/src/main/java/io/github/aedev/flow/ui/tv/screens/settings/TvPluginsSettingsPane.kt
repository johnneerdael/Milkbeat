package io.github.aedev.flow.ui.tv.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.install.PendingInstall
import io.github.aedev.flow.plugin.install.PluginUpdate
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.plugin.registry.ownTracksOnlyAudio
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSearchField
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.components.TvSelectionRow
import io.github.aedev.flow.ui.tv.components.TvToggleRow
import io.github.aedev.flow.ui.tv.focus.ProvideTvColumnPivot
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.MetadataSurface
import nl.neerdael.milkbeat.plugin.PluginConnectionRow
import nl.neerdael.milkbeat.plugin.PluginConnectionTestState
import nl.neerdael.milkbeat.plugin.PluginManifest

private enum class ProviderRole { AUDIO, VIDEO }

/**
 * Settings, Plugins: the plugins that provide music, audio and videos, adding one from a link (with
 * its permissions to agree to first), and each plugin's sign-in and removal.
 */
@Composable
fun TvPluginsSettingsPane(
    onSignIn: (pluginId: String, methodId: String, downloadMicroG: Boolean) -> Unit,
    onOpenAppUpdates: () -> Unit,
    modifier: Modifier = Modifier,
    homeRevision: Int = 0,
    viewModel: TvPluginsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playHistory by viewModel.playHistoryEnabled.collectAsStateWithLifecycle()
    val automaticUpdates by viewModel.automaticUpdates.collectAsStateWithLifecycle()
    var openPlugin by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable { mutableStateOf<ProviderRole?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    var connectionUrl by rememberSaveable { mutableStateOf("") }
    val consent = state.adding as? AddPluginState.Consent
    LaunchedEffect(homeRevision) {
        if (viewModel.consumeHomeRequest(homeRevision)) {
            openPlugin = null
            choosing = null
            url = ""
            connectionUrl = ""
        }
    }

    // Back closes the page open inside Plugins before it may leave Settings, as a picker panel does.
    BackHandler(enabled = consent != null || choosing != null || openPlugin != null) {
        when {
            consent != null -> {
                viewModel.cancelAdd()
            }

            choosing != null -> {
                choosing = null
            }

            state.connections.page is ConnectionsPage.Review || state.connections.page is ConnectionsPage.EditUrl -> {
                viewModel.editConnectionUrl((state.connections.page as? ConnectionsPage.EditUrl)?.connectionId)
            }

            state.connections.page != null -> {
                viewModel.closeConnections()
            }

            else -> {
                openPlugin = null
            }
        }
    }

    val verifying = state.adding as? AddPluginState.Verifying
    // Remembered across the browser check, so the list takes focus back when it returns.
    val listFocus = Modifier.tvInitialFocus(choosing, openPlugin, consent?.pending, verifying, onFirstComposition = false)
    if (verifying != null) {
        TvPluginVerificationPane(
            page = verifying.verification.page,
            onFile = viewModel::verified,
            onCancel = viewModel::cancelAdd,
            modifier = modifier,
        )
        return
    }
    ProvideTvColumnPivot {
        LazyColumn(
            modifier =
                modifier
                    .fillMaxSize()
                    .then(listFocus)
                    .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val plugin = state.plugins.firstOrNull { it.id == openPlugin }
            val role = choosing
            when {
                consent != null -> {
                    consentItems(consent.pending, onInstall = viewModel::install, onCancel = viewModel::cancelAdd)
                }

                role != null -> {
                    chooserItems(
                        role,
                        state.plugins,
                        state.selection,
                        onSelect = { selection ->
                            viewModel.select(selection)
                            if (role != ProviderRole.AUDIO) choosing = null
                        },
                        onDone = { choosing = null },
                        onAudioChange = viewModel::mutateSelection,
                    )
                }

                plugin != null -> {
                    val openConnections = state.connections.pluginId == plugin.id && state.connections.page != null
                    if (openConnections) {
                        connectionItems(
                            plugin = plugin,
                            connections = state.connections,
                            account = state.accounts[plugin.id],
                            connectionUrl = connectionUrl,
                            onUrlChange = { connectionUrl = it },
                            onUrl = viewModel::reviewConnection,
                            onReviewAccount = viewModel::setConnectionReviewAccount,
                            onApprove = viewModel::approveConnection,
                            onEdit = {
                                connectionUrl = ""
                                viewModel.editConnectionUrl(it)
                            },
                            onEnabled = viewModel::setConnectionEnabled,
                            onRemove = viewModel::removeConnection,
                            onTest = viewModel::testConnection,
                            onDone = viewModel::closeConnections,
                        )
                    } else {
                        if (state.accounts[plugin.id] is ProviderAccount.SignedIn &&
                            setOf(MetadataSurface.LIBRARY, MetadataSurface.TRACKS).all {
                                it in
                                    plugin.manifest.roles.metadata
                                        ?.surfaces
                                        .orEmpty()
                            }
                        ) {
                            item(key = "preload-${plugin.id}") { TvPlaylistPreloadItem(plugin, viewModel.preloadJobs) }
                        }
                        detailItems(
                            plugin = plugin,
                            account = state.accounts[plugin.id],
                            playHistory = playHistory,
                            microGDownloadable = viewModel.microGDownloadable,
                            replacementInstalled = { id -> state.plugins.any { it.id == id } },
                            onAddReplacement = { code ->
                                openPlugin = null
                                viewModel.fetch(code)
                            },
                            onPlayHistoryChange = viewModel::setPlayHistoryEnabled,
                            onSignIn = { method, download -> onSignIn(plugin.id, method, download) },
                            onSignOut = { viewModel.signOut(plugin.id) },
                            onConnections =
                                if (plugin.manifest.permissions.connections) {
                                    { viewModel.openConnections(plugin.id) }
                                } else {
                                    null
                                },
                            onRemove = {
                                viewModel.remove(plugin.id)
                                openPlugin = null
                            },
                        )
                    }
                }

                else -> {
                    overviewItems(
                        state = state,
                        url = url,
                        onUrlChange = { url = it },
                        onFetch = { viewModel.fetch(url) },
                        onChoose = { choosing = it },
                        onOpen = { openPlugin = it },
                        automaticUpdates = automaticUpdates,
                        onAutomaticUpdatesChange = viewModel::setAutomaticUpdates,
                        onUpdateAll = viewModel::updateAll,
                        onUpdate = viewModel::update,
                        onUpdateApp = onOpenAppUpdates.takeIf { viewModel.appUpdatesAvailable },
                    )
                }
            }
        }
    }
}

private fun LazyListScope.overviewItems(
    state: TvPluginsState,
    url: String,
    onUrlChange: (String) -> Unit,
    onFetch: () -> Unit,
    onChoose: (ProviderRole) -> Unit,
    onOpen: (String) -> Unit,
    automaticUpdates: Boolean,
    onAutomaticUpdatesChange: (Boolean) -> Unit,
    onUpdateAll: () -> Unit,
    onUpdate: (PluginUpdate) -> Unit,
    onUpdateApp: (() -> Unit)?,
) {
    item(key = "providers-header") { TvSectionHeader(stringResource(R.string.tv_plugins_providers)) }
    ProviderRole.entries.forEach { role ->
        item(key = "provider-$role") {
            TvNavRow(
                label = stringResource(role.label()),
                value = providerNames(role, state.selection, state.plugins) ?: stringResource(R.string.tv_plugins_none),
                onClick = { onChoose(role) },
                scrollingValue = true,
            )
        }
    }
    item(
        key = "installed-header",
    ) { TvSectionHeader(stringResource(R.string.tv_plugins_installed), modifier = Modifier.padding(top = 12.dp)) }
    if (state.plugins.isEmpty()) {
        item(key = "none-installed") {
            Text(
                text = stringResource(R.string.tv_plugins_none_installed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    items(state.plugins, key = { "plugin-${it.id}" }) { plugin ->
        TvNavRow(
            label = plugin.manifest.name,
            supportingText = rolesLabel(plugin.manifest),
            value = plugin.manifest.version,
            leadingIcon = Icons.Outlined.Extension,
            onClick = { onOpen(plugin.id) },
        )
    }
    if (state.plugins.isNotEmpty()) {
        pluginUpdateItems(
            state = state.updates,
            automatic = automaticUpdates,
            onAutomaticChange = onAutomaticUpdatesChange,
            onUpdateAll = onUpdateAll,
            onUpdate = onUpdate,
            onUpdateApp = onUpdateApp,
        )
    }
    item(key = "add-header") { TvSectionHeader(stringResource(R.string.tv_plugins_add), modifier = Modifier.padding(top = 12.dp)) }
    item(key = "add-url") {
        TvSearchField(
            query = url,
            onQueryChange = onUrlChange,
            onSearch = onFetch,
            placeholder = stringResource(R.string.tv_plugins_url_hint),
            leadingIcon = Icons.Outlined.Link,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    item(key = "add-go") {
        TvNavRow(
            label = stringResource(R.string.tv_plugins_add),
            supportingText = stringResource(R.string.tv_plugins_add_subtitle),
            leadingIcon = Icons.Outlined.Add,
            onClick = onFetch,
        )
    }
    when (val adding = state.adding) {
        AddPluginState.Fetching -> {
            item(key = "add-status") { PluginStatusText(stringResource(R.string.tv_plugins_fetching)) }
        }

        is AddPluginState.RequiresAppUpdate -> {
            requiresAppUpdateItems(adding.pluginName, onUpdateApp)
        }

        is AddPluginState.Failed -> {
            item(key = "add-status") {
                PluginStatusText(adding.messageResource?.let { stringResource(it) } ?: adding.message, error = true)
            }
        }

        else -> {
            Unit
        }
    }
}

private fun LazyListScope.consentItems(
    pending: PendingInstall,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
) {
    val manifest = pending.pack.manifest
    item(key = "consent-title") {
        TvSectionHeader(
            stringResource(
                when {
                    pending.isReinstall -> R.string.tv_plugins_reinstall_title
                    pending.isUpdate -> R.string.tv_plugins_update_title
                    else -> R.string.tv_plugins_install_title
                },
                manifest.name,
                manifest.version,
            ),
        )
    }
    manifest.author?.let { item(key = "consent-author") { PluginStatusText(stringResource(R.string.tv_plugins_by, it.name)) } }
    manifest.description?.let { item(key = "consent-description") { PluginStatusText(it) } }
    item(key = "consent-roles") { PluginStatusText(stringResource(R.string.tv_plugins_provides, rolesLabel(manifest))) }
    val network = if (pending.isUpdate) pending.newNetwork else manifest.permissions.network
    if (network.isNotEmpty()) {
        item(key = "consent-network") {
            PluginStatusText(stringResource(R.string.tv_plugins_network, network.joinToString(", ")))
        }
    }
    val browser = if (pending.isUpdate) pending.newBrowser else manifest.permissions.browser
    if (browser.isNotEmpty()) {
        item(key = "consent-browser") {
            PluginStatusText(stringResource(R.string.tv_plugins_browser, browser.joinToString(", ")))
        }
    }
    item(key = "consent-actions") {
        // A plugin link opens this from outside the app, with focus nowhere near it.
        val installFocus = remember { FocusRequester() }
        LaunchedEffect(pending) {
            withFrameNanos { }
            runCatching { installFocus.requestFocus() }
        }
        Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TvButton(
                text =
                    stringResource(
                        when {
                            pending.isReinstall -> R.string.tv_plugins_reinstall
                            pending.isUpdate -> R.string.tv_plugins_update
                            else -> R.string.tv_plugins_install
                        },
                    ),
                onClick = onInstall,
                modifier = Modifier.focusRequester(installFocus),
            )
            TvButton(text = stringResource(R.string.cancel), onClick = onCancel)
        }
    }
}

private fun LazyListScope.chooserItems(
    role: ProviderRole,
    plugins: List<InstalledPlugin>,
    selection: ProviderSelection,
    onSelect: (ProviderSelection) -> Unit,
    onDone: () -> Unit,
    onAudioChange: ((ProviderSelection) -> ProviderSelection) -> Unit,
) {
    item(key = "chooser-title") {
        TvSectionHeader(stringResource(if (role == ProviderRole.AUDIO) R.string.tv_plugins_audio_priority else role.label()))
    }
    val eligible = plugins.filter { role.offeredBy(it.manifest) }
    if (role == ProviderRole.AUDIO) {
        audioProviderChooserItems(eligible, selection, onAudioChange, onDone)
        return
    }
    if (role != ProviderRole.AUDIO) {
        item(key = "chooser-none") {
            TvSelectionRow(
                label = stringResource(R.string.tv_plugins_none),
                selected = role.current(selection).isEmpty(),
                onClick = { onSelect(role.with(selection, null)) },
            )
        }
    }
    items(eligible, key = { "chooser-${it.id}" }) { plugin ->
        TvSelectionRow(
            label = plugin.manifest.name,
            selected = plugin.id in role.current(selection),
            onClick = { onSelect(role.with(selection, plugin.id)) },
        )
    }
}

@Composable
internal fun PluginStatusText(
    text: String,
    error: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun LazyListScope.connectionItems(
    plugin: InstalledPlugin,
    connections: PluginConnectionsState,
    account: ProviderAccount?,
    connectionUrl: String,
    onUrlChange: (String) -> Unit,
    onUrl: (String) -> Unit,
    onReviewAccount: (Boolean) -> Unit,
    onApprove: () -> Unit,
    onEdit: (String?) -> Unit,
    onEnabled: (String, Boolean) -> Unit,
    onRemove: (PluginConnectionRow) -> Unit,
    onTest: (String) -> Unit,
    onDone: () -> Unit,
) {
    when (val page = connections.page) {
        is ConnectionsPage.EditUrl -> {
            item(key = "connection-edit-title") {
                TvSectionHeader(
                    stringResource(
                        if (page.connectionId == null) R.string.tv_plugins_connections_add else R.string.tv_plugins_connections_replace,
                        plugin.manifest.name,
                    ),
                )
            }
            item(key = "connection-edit-hint") {
                PluginStatusText(stringResource(R.string.tv_plugins_connections_hint))
            }
            item(key = "connection-edit-url") {
                TvSearchField(
                    query = connectionUrl,
                    onQueryChange = onUrlChange,
                    onSearch = { onUrl(connectionUrl) },
                    placeholder = stringResource(R.string.tv_plugins_connections_url_hint),
                    leadingIcon = Icons.Outlined.Link,
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item(key = "connection-edit-review") {
                TvNavRow(
                    label = stringResource(R.string.tv_plugins_connections_review),
                    leadingIcon = Icons.Outlined.Add,
                    onClick = { onUrl(connectionUrl) },
                )
            }
        }

        is ConnectionsPage.Review -> {
            item(key = "connection-review-title") { TvSectionHeader(stringResource(R.string.tv_plugins_connections_review_title)) }
            item(key = "connection-review-name") {
                PluginStatusText(page.manifest.name ?: page.manifest.id)
            }
            item(key = "connection-review-origin") {
                PluginStatusText(originOf(page.manifest.url))
            }
            item(key = "connection-review-id") {
                PluginStatusText(stringResource(R.string.tv_plugins_connections_addon_id, page.manifest.id))
            }
            if (account is ProviderAccount.SignedIn) {
                item(key = "connection-review-account") {
                    TvToggleRow(
                        label = stringResource(R.string.tv_plugins_connections_use_account),
                        supportingText = stringResource(R.string.tv_plugins_connections_use_account_summary),
                        checked = page.useAccount,
                        onCheckedChange = onReviewAccount,
                    )
                }
            }
            item(key = "connection-review-status") {
                if (connections.busy) PluginStatusText(stringResource(R.string.tv_plugins_connections_saving))
                connections.failure?.let { PluginStatusText(it, error = true) }
            }
            item(key = "connection-review-actions") {
                val approveFocus = remember { FocusRequester() }
                LaunchedEffect(page) {
                    withFrameNanos { }
                    runCatching { approveFocus.requestFocus() }
                }
                Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvButton(
                        text = stringResource(R.string.tv_plugins_connections_approve),
                        onClick = onApprove,
                        modifier = Modifier.focusRequester(approveFocus),
                    )
                    TvButton(text = stringResource(R.string.cancel), onClick = { onEdit(page.connectionId) })
                }
            }
        }

        else -> {
            item(key = "connections-title") {
                TvSectionHeader(stringResource(R.string.tv_plugins_connections, plugin.manifest.name))
            }
            if (connections.busy && !connections.loaded) {
                item(key = "connections-loading") { PluginStatusText(stringResource(R.string.tv_plugins_connections_loading)) }
            } else if (connections.rows.isEmpty() && connections.loaded) {
                item(key = "connections-none") { PluginStatusText(stringResource(R.string.tv_plugins_connections_none)) }
            }
            connections.rows.forEach { row ->
                item(key = "connection-toggle-${row.id}") {
                    TvToggleRow(
                        label = row.title,
                        supportingText = row.host,
                        checked = row.enabled,
                        onCheckedChange = { onEnabled(row.id, it) },
                    )
                }
                item(key = "connection-actions-${row.id}") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        TvButton(text = stringResource(R.string.tv_plugins_connections_test), onClick = { onTest(row.id) })
                        TvButton(text = stringResource(R.string.tv_plugins_connections_replace_short), onClick = { onEdit(row.id) })
                        TvButton(text = stringResource(R.string.remove), onClick = { onRemove(row) })
                    }
                }
                connections.testResult?.takeIf { it.first == row.id }?.let { (_, result) ->
                    item(key = "connection-test-${row.id}") {
                        PluginStatusText(
                            stringResource(
                                when (result) {
                                    PluginConnectionTestState.VERIFIED -> R.string.tv_plugins_connections_test_verified
                                    PluginConnectionTestState.LOOKUP_FAILED -> R.string.tv_plugins_connections_test_lookup
                                    PluginConnectionTestState.UNAUTHORIZED -> R.string.tv_plugins_connections_test_unauthorized
                                    PluginConnectionTestState.MANIFEST_FAILED -> R.string.tv_plugins_connections_test_manifest
                                    PluginConnectionTestState.SIGNED_OUT -> R.string.tv_plugins_connections_test_signed_out
                                },
                                row.title,
                            ),
                        )
                    }
                }
            }
            connections.failure?.let { item(key = "connections-failure") { PluginStatusText(it, error = true) } }
            item(key = "connection-add") {
                TvNavRow(
                    label = stringResource(R.string.tv_plugins_connections_add_short),
                    leadingIcon = Icons.Outlined.Add,
                    onClick = { onEdit(null) },
                )
            }
            item(key = "connection-done") { TvNavRow(label = stringResource(R.string.done), onClick = onDone) }
        }
    }
}

private fun originOf(url: String): String = url.removePrefix("https://").takeIf { url.startsWith("https://") }?.substringBefore('/') ?: url

@Composable
private fun rolesLabel(manifest: PluginManifest): String =
    listOfNotNull(
        manifest.roles.metadata?.let { stringResource(R.string.tv_plugins_role_metadata) },
        manifest.roles.audio?.let { stringResource(R.string.tv_plugins_role_audio) },
        manifest.roles.video?.let { stringResource(R.string.tv_plugins_role_video) },
    ).joinToString(", ")

@Composable
private fun providerNames(
    role: ProviderRole,
    selection: ProviderSelection,
    plugins: List<InstalledPlugin>,
): String? {
    val ordered = role.current(selection).mapNotNull { id -> plugins.firstOrNull { it.id == id }?.manifest?.name }
    val ownTracksOnly =
        if (role == ProviderRole.AUDIO) {
            ownTracksOnlyAudio(plugins, selection).map { stringResource(R.string.tv_plugins_audio_own_tracks_value, it.manifest.name) }
        } else {
            emptyList()
        }
    return (ordered + ownTracksOnly).takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun ProviderRole.label(): Int =
    when (this) {
        ProviderRole.AUDIO -> R.string.tv_plugins_audio
        ProviderRole.VIDEO -> R.string.tv_plugins_video
    }

private fun ProviderRole.offeredBy(manifest: PluginManifest): Boolean =
    when (this) {
        ProviderRole.AUDIO -> manifest.roles.audio != null
        ProviderRole.VIDEO -> manifest.roles.video != null
    }

private fun ProviderRole.current(selection: ProviderSelection): List<String> =
    when (this) {
        ProviderRole.AUDIO -> selection.audio
        ProviderRole.VIDEO -> listOfNotNull(selection.video)
    }

private fun ProviderRole.with(
    selection: ProviderSelection,
    pluginId: String?,
): ProviderSelection {
    check(this != ProviderRole.AUDIO)
    return selection.copy(video = pluginId)
}
