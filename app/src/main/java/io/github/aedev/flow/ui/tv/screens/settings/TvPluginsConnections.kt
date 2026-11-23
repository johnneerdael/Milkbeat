package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Link
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSearchField
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.components.TvToggleRow
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.PluginConnectionRow
import nl.neerdael.milkbeat.plugin.PluginConnectionTestState

/**
 * Settings, Plugins: one plugin's reviewed external add-ons — the editor, the review, approval and
 * the configured link rows.
 */
internal fun LazyListScope.connectionItems(
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
            item(key = "connection-edit-status") {
                if (connections.busy) PluginStatusText(stringResource(R.string.tv_plugins_connections_reviewing))
                connections.failure?.let { PluginStatusText(it, error = true) }
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
