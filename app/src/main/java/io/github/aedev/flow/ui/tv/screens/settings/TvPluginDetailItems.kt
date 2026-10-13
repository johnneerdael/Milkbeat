package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.catalog.KnownProviders
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.components.TvToggleRow
import nl.neerdael.milkbeat.catalog.ProviderAccount
import nl.neerdael.milkbeat.plugin.AndroidAccountMethod

internal fun LazyListScope.detailItems(
    plugin: InstalledPlugin,
    account: ProviderAccount?,
    playHistory: Boolean,
    microGDownloadable: Boolean,
    replacementInstalled: (String) -> Boolean,
    onAddReplacement: (code: String) -> Unit,
    onPlayHistoryChange: (Boolean) -> Unit,
    onSignIn: (methodId: String, downloadMicroG: Boolean) -> Unit,
    onSignOut: () -> Unit,
    onRemove: () -> Unit,
) {
    item(key = "detail-title") { TvSectionHeader(plugin.manifest.name) }
    item(key = "detail-version") { PluginStatusText(stringResource(R.string.tv_plugins_version, plugin.manifest.version)) }
    plugin.manifest.description?.let { item(key = "detail-description") { PluginStatusText(it) } }
    KnownProviders.replacementFor(plugin.id)?.let { replacement ->
        val installed = replacementInstalled(replacement.pluginId)
        item(key = "detail-replaced") {
            PluginStatusText(
                if (installed) {
                    stringResource(R.string.tv_plugins_replaced_installed, plugin.manifest.name)
                } else {
                    stringResource(R.string.tv_plugins_replaced, plugin.manifest.name, replacement.code)
                },
            )
        }
        if (!installed) {
            item(key = "detail-add-replacement") {
                TvNavRow(label = stringResource(R.string.tv_plugins_add_youtube), onClick = { onAddReplacement(replacement.code) })
            }
        }
    }
    if (plugin.manifest.signIn.isNotEmpty()) {
        item(key = "detail-account") {
            PluginStatusText(
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
                TvNavRow(label = method.label, onClick = { onSignIn(method.id, false) })
            }
            val microGSignIn = plugin.manifest.signIn.firstOrNull { it is AndroidAccountMethod }
            if (microGSignIn != null && microGDownloadable) {
                item(key = "detail-download-microg") {
                    TvNavRow(
                        label = stringResource(R.string.tv_microg_download_action),
                        supportingText = stringResource(R.string.tv_plugins_download_microg_summary),
                        onClick = { onSignIn(microGSignIn.id, true) },
                    )
                }
            }
        }
    }
    item(key = "detail-remove") { TvNavRow(label = stringResource(R.string.remove), onClick = onRemove) }
}
