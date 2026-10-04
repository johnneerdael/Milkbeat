package io.github.aedev.flow.ui.tv.screens.settings

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
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.ProviderSelection
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
import nl.neerdael.milkbeat.plugin.PluginManifest

private enum class ProviderRole { MUSIC, AUDIO, VIDEO }

/**
 * Settings, Plugins: the plugins that provide music, audio and videos, adding one from a link (with
 * its permissions to agree to first), and each plugin's sign-in and removal.
 */
@Composable
fun TvPluginsSettingsPane(
    onSignIn: (pluginId: String, methodId: String) -> Unit,
    modifier: Modifier = Modifier,
    homeRevision: Int = 0,
    viewModel: TvPluginsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playHistory by viewModel.playHistoryEnabled.collectAsStateWithLifecycle()
    var openPlugin by rememberSaveable { mutableStateOf<String?>(null) }
    var choosing by rememberSaveable { mutableStateOf<ProviderRole?>(null) }
    var url by rememberSaveable { mutableStateOf("") }
    val consent = state.adding as? AddPluginState.Consent
    LaunchedEffect(homeRevision) {
        if (viewModel.consumeHomeRequest(homeRevision)) {
            openPlugin = null
            choosing = null
            url = ""
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
                    if (plugin.manifest.roles.metadata
                            ?.personalCollections == true
                    ) {
                        state.plugins
                            .filter {
                                it.enabled && it.manifest.roles.metadata
                                    ?.privatePlaylistImport == true &&
                                    it.manifest.roles.audio != null
                            }.forEach { target ->
                                item(key = "mirror-${target.id}") {
                                    TvPlaylistMirrorOption(
                                        target,
                                        io.github.aedev.flow.plugin.mirror.PlaylistMirrorStore.pairId(
                                            plugin.id,
                                            target.id,
                                        ) in state.mirrorPairs,
                                        viewModel.mirrors.available(plugin.id, target.id),
                                        { viewModel.setMirrorEnabled(plugin.id, target.id, it) },
                                    )
                                }
                            }
                    }
                    detailItems(
                        plugin = plugin,
                        account = state.accounts[plugin.id],
                        playHistory = playHistory,
                        onPlayHistoryChange = viewModel::setPlayHistoryEnabled,
                        onSignIn = { method -> onSignIn(plugin.id, method) },
                        onSignOut = { viewModel.signOut(plugin.id) },
                        onRemove = {
                            viewModel.remove(plugin.id)
                            openPlugin = null
                        },
                    )
                }

                else -> {
                    overviewItems(
                        state = state,
                        url = url,
                        onUrlChange = { url = it },
                        onFetch = { viewModel.fetch(url) },
                        onChoose = { choosing = it },
                        onOpen = { openPlugin = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun TvPlaylistMirrorOption(
    target: InstalledPlugin,
    enabled: Boolean,
    available: Boolean,
    onEnabled: (Boolean) -> Unit,
) {
    TvToggleRow(
        label = stringResource(R.string.playlist_mirror_option, target.manifest.name),
        checked = enabled,
        onCheckedChange = { if (available || !it) onEnabled(it) },
        supportingText = stringResource(if (available) R.string.playlist_mirror_description else R.string.playlist_mirror_sign_in),
    )
}

private fun LazyListScope.overviewItems(
    state: TvPluginsState,
    url: String,
    onUrlChange: (String) -> Unit,
    onFetch: () -> Unit,
    onChoose: (ProviderRole) -> Unit,
    onOpen: (String) -> Unit,
) {
    item(key = "providers-header") { TvSectionHeader(stringResource(R.string.tv_plugins_providers)) }
    ProviderRole.entries.forEach { role ->
        item(key = "provider-$role") {
            TvNavRow(
                label = stringResource(role.label()),
                value = providerNames(role, state.selection, state.plugins) ?: stringResource(R.string.tv_plugins_none),
                onClick = { onChoose(role) },
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
            item(key = "add-status") { StatusText(stringResource(R.string.tv_plugins_fetching)) }
        }

        is AddPluginState.Failed -> {
            item(key = "add-status") {
                StatusText(adding.messageResource?.let { stringResource(it) } ?: adding.message, error = true)
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
                if (pending.isUpdate) R.string.tv_plugins_update_title else R.string.tv_plugins_install_title,
                manifest.name,
                manifest.version,
            ),
        )
    }
    manifest.author?.let { item(key = "consent-author") { StatusText(stringResource(R.string.tv_plugins_by, it.name)) } }
    manifest.description?.let { item(key = "consent-description") { StatusText(it) } }
    item(key = "consent-roles") { StatusText(stringResource(R.string.tv_plugins_provides, rolesLabel(manifest))) }
    val network = if (pending.isUpdate) pending.newNetwork else manifest.permissions.network
    if (network.isNotEmpty()) {
        item(key = "consent-network") {
            StatusText(stringResource(R.string.tv_plugins_network, network.joinToString(", ")))
        }
    }
    val browser = if (pending.isUpdate) pending.newBrowser else manifest.permissions.browser
    if (browser.isNotEmpty()) {
        item(key = "consent-browser") {
            StatusText(stringResource(R.string.tv_plugins_browser, browser.joinToString(", ")))
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
                text = stringResource(if (pending.isUpdate) R.string.tv_plugins_update else R.string.tv_plugins_install),
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

private fun LazyListScope.detailItems(
    plugin: InstalledPlugin,
    account: ProviderAccount?,
    playHistory: Boolean,
    onPlayHistoryChange: (Boolean) -> Unit,
    onSignIn: (String) -> Unit,
    onSignOut: () -> Unit,
    onRemove: () -> Unit,
) {
    item(key = "detail-title") { TvSectionHeader(plugin.manifest.name) }
    item(key = "detail-version") { StatusText(stringResource(R.string.tv_plugins_version, plugin.manifest.version)) }
    plugin.manifest.description?.let { item(key = "detail-description") { StatusText(it) } }
    if (plugin.manifest.signIn.isNotEmpty()) {
        item(key = "detail-account") {
            StatusText(
                when (account) {
                    is ProviderAccount.SignedIn -> {
                        account.name?.let { stringResource(R.string.tv_plugins_signed_in_as, it) }
                            ?: stringResource(R.string.tv_plugins_signed_in)
                    }

                    ProviderAccount.Expired -> {
                        stringResource(R.string.tv_plugins_sign_in_expired)
                    }

                    else -> {
                        stringResource(R.string.tv_plugins_not_signed_in)
                    }
                },
            )
        }
        if (account is ProviderAccount.SignedIn) {
            val reportsPlays =
                plugin.manifest.roles.audio
                    ?.reportPlayback == true || plugin.manifest.roles.video
                    ?.reportPlayback == true
            if (reportsPlays) {
                item(key = "detail-play-history") {
                    TvToggleRow(
                        label = stringResource(R.string.tv_account_play_history, plugin.manifest.name),
                        supportingText = stringResource(R.string.tv_account_play_history_summary, plugin.manifest.name),
                        checked = playHistory,
                        onCheckedChange = onPlayHistoryChange,
                    )
                }
            }
            item(key = "detail-sign-out") { TvNavRow(label = stringResource(R.string.tv_plugins_sign_out), onClick = onSignOut) }
        } else {
            items(plugin.manifest.signIn, key = { "detail-sign-in-${it.id}" }) { method ->
                TvNavRow(label = method.label, onClick = { onSignIn(method.id) })
            }
        }
    }
    item(key = "detail-remove") { TvNavRow(label = stringResource(R.string.remove), onClick = onRemove) }
}

@Composable
private fun StatusText(
    text: String,
    error: Boolean = false,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun rolesLabel(manifest: PluginManifest): String =
    listOfNotNull(
        manifest.roles.metadata?.let { stringResource(R.string.tv_plugins_role_metadata) },
        manifest.roles.audio?.let { stringResource(R.string.tv_plugins_role_audio) },
        manifest.roles.video?.let { stringResource(R.string.tv_plugins_role_video) },
    ).joinToString(", ")

private fun providerNames(
    role: ProviderRole,
    selection: ProviderSelection,
    plugins: List<InstalledPlugin>,
): String? =
    role
        .current(selection)
        .mapNotNull { id -> plugins.firstOrNull { it.id == id }?.manifest?.name }
        .takeIf { it.isNotEmpty() }
        ?.joinToString(", ")

private fun ProviderRole.label(): Int =
    when (this) {
        ProviderRole.MUSIC -> R.string.tv_plugins_metadata
        ProviderRole.AUDIO -> R.string.tv_plugins_audio
        ProviderRole.VIDEO -> R.string.tv_plugins_video
    }

private fun ProviderRole.offeredBy(manifest: PluginManifest): Boolean =
    when (this) {
        ProviderRole.MUSIC -> manifest.roles.metadata != null
        ProviderRole.AUDIO -> manifest.roles.audio != null
        ProviderRole.VIDEO -> manifest.roles.video != null
    }

private fun ProviderRole.current(selection: ProviderSelection): List<String> =
    when (this) {
        ProviderRole.MUSIC -> listOfNotNull(selection.metadata)
        ProviderRole.AUDIO -> selection.audio
        ProviderRole.VIDEO -> listOfNotNull(selection.video)
    }

private fun ProviderRole.with(
    selection: ProviderSelection,
    pluginId: String?,
): ProviderSelection {
    check(this != ProviderRole.AUDIO)
    return if (this == ProviderRole.MUSIC) selection.copy(metadata = pluginId) else selection.copy(video = pluginId)
}
