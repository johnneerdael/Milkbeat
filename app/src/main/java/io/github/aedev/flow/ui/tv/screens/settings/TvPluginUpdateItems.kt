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
import io.github.aedev.flow.ui.tv.components.TvNavRow

/** Checking the plugin publisher for newer versions, and one row per update found to review and install it. */
internal fun LazyListScope.pluginUpdateItems(
    state: PluginUpdatesState,
    onCheck: () -> Unit,
    onUpdate: (PluginUpdate) -> Unit,
) {
    item(key = "updates-check") {
        TvNavRow(
            label = stringResource(R.string.tv_plugins_check_updates),
            supportingText = updatesStatus(state),
            leadingIcon = Icons.Outlined.Refresh,
            onClick = onCheck,
        )
    }
    val found = (state as? PluginUpdatesState.Checked)?.updates.orEmpty()
    items(found, key = { "update-${it.pluginId}" }) { update ->
        TvNavRow(
            label = stringResource(R.string.tv_plugins_update_to, update.name),
            value = update.version,
            leadingIcon = Icons.Outlined.SystemUpdate,
            onClick = { onUpdate(update) },
        )
    }
}

@Composable
private fun updatesStatus(state: PluginUpdatesState): String? =
    when (state) {
        PluginUpdatesState.Idle -> {
            null
        }

        PluginUpdatesState.Checking -> {
            stringResource(R.string.tv_plugins_checking_updates)
        }

        is PluginUpdatesState.Checked -> {
            if (state.updates.isEmpty()) {
                stringResource(R.string.tv_plugins_up_to_date)
            } else {
                pluralStringResource(R.plurals.tv_plugins_updates_available, state.updates.size, state.updates.size)
            }
        }

        is PluginUpdatesState.Failed -> {
            stringResource(state.messageResource ?: R.string.tv_plugins_update_check_failed)
        }
    }
