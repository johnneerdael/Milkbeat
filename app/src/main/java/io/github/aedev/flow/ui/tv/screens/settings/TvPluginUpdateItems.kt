package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.install.PluginUpdate
import io.github.aedev.flow.plugin.install.PluginUpdatesState
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvToggleRow

/**
 * Keeping plugins current: whether they update by themselves, updating them all now, one row per
 * update left over (it asks for new permissions or a browser check, or its download failed) to install
 * it by hand, and one per update that waits for a newer Milkbeat, which [onUpdateApp] opens when this
 * build updates itself.
 */
internal fun LazyListScope.pluginUpdateItems(
    state: PluginUpdatesState,
    automatic: Boolean,
    onAutomaticChange: (Boolean) -> Unit,
    onUpdateAll: () -> Unit,
    onUpdate: (PluginUpdate) -> Unit,
    onUpdateApp: (() -> Unit)?,
) {
    item(key = "updates-automatic") {
        TvToggleRow(
            label = stringResource(R.string.tv_plugins_update_automatic),
            supportingText = stringResource(R.string.tv_plugins_update_automatic_subtitle),
            checked = automatic,
            onCheckedChange = onAutomaticChange,
        )
    }
    item(key = "updates-all") {
        TvNavRow(
            label = stringResource(R.string.tv_plugins_update_all),
            supportingText = updatesStatus(state),
            leadingIcon = Icons.Outlined.Refresh,
            onClick = { if (state != PluginUpdatesState.Checking) onUpdateAll() },
        )
    }
    val left = (state as? PluginUpdatesState.Checked)?.let { it.updates + it.failed }.orEmpty()
    items(left, key = { "update-${it.pluginId}" }) { update ->
        TvNavRow(
            label = stringResource(R.string.tv_plugins_update_to, update.name),
            value = update.version,
            leadingIcon = Icons.Outlined.SystemUpdate,
            onClick = { onUpdate(update) },
        )
    }
    val waiting = (state as? PluginUpdatesState.Checked)?.requiresAppUpdate.orEmpty()
    items(waiting, key = { "update-app-${it.pluginId}" }) { update ->
        TvNavRow(
            label = stringResource(R.string.tv_plugins_update_to, update.name),
            value = update.version,
            supportingText = stringResource(R.string.tv_plugins_update_requires_app_update),
            leadingIcon = Icons.Outlined.SystemUpdate,
            onClick = { onUpdateApp?.invoke() },
        )
    }
}

/** A plugin refused because it needs a newer Milkbeat, with the way to [onUpdateApp] when this build updates itself. */
internal fun LazyListScope.requiresAppUpdateItems(
    pluginName: String,
    onUpdateApp: (() -> Unit)?,
) {
    item(key = "add-status") {
        PluginStatusText(stringResource(R.string.tv_plugins_requires_app_update, pluginName), error = true)
    }
    if (onUpdateApp != null) {
        item(key = "add-update-app") {
            TvNavRow(
                label = stringResource(R.string.tv_plugins_update_app),
                supportingText = stringResource(R.string.tv_plugins_update_app_subtitle),
                leadingIcon = Icons.Outlined.SystemUpdate,
                onClick = onUpdateApp,
            )
        }
    }
}

@Composable
private fun updatesStatus(state: PluginUpdatesState): String? =
    when (state) {
        PluginUpdatesState.Idle -> {
            null
        }

        PluginUpdatesState.Checking -> {
            stringResource(R.string.tv_plugins_updating)
        }

        is PluginUpdatesState.Checked -> {
            when {
                state.updates.isNotEmpty() -> {
                    pluralStringResource(R.plurals.tv_plugins_updates_need_review, state.updates.size, state.updates.size)
                }

                state.failed.isNotEmpty() -> {
                    pluralStringResource(R.plurals.tv_plugins_updates_failed, state.failed.size, state.failed.size)
                }

                state.requiresAppUpdate.isNotEmpty() -> {
                    val count = state.requiresAppUpdate.size
                    pluralStringResource(R.plurals.tv_plugins_updates_require_app_update, count, count)
                }

                state.installed.isNotEmpty() -> {
                    pluralStringResource(R.plurals.tv_plugins_updated, state.installed.size, state.installed.size)
                }

                else -> {
                    stringResource(R.string.tv_plugins_up_to_date)
                }
            }
        }

        is PluginUpdatesState.Failed -> {
            stringResource(state.messageResource ?: R.string.tv_plugins_update_check_failed)
        }
    }
