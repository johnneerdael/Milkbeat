package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.plugin.registry.InstalledPlugin
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.ui.tv.components.TvButton
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.components.TvToggleRow
import java.util.Collections

internal fun LazyListScope.audioProviderChooserItems(
    plugins: List<InstalledPlugin>,
    selection: ProviderSelection,
    onSelect: ((ProviderSelection) -> ProviderSelection) -> Unit,
    onDone: () -> Unit,
) {
    val ordered = selection.audio.distinct().mapNotNull { id -> plugins.firstOrNull { it.id == id && it.enabled } }
    item(key = "audio-order-title") {
        Text(
            stringResource(R.string.tv_plugins_audio_priority_summary),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    items(ordered, key = { "audio-order-${it.id}" }) { plugin ->
        val index = ordered.indexOfFirst { it.id == plugin.id }
        val rowFocus = remember { FocusRequester() }
        var moveDirection by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(index) {
            val direction = moveDirection
            if ((direction == -1 && index == 0) || (direction == 1 && index == ordered.lastIndex)) {
                withFrameNanos { }
                rowFocus.requestFocus()
            }
            moveDirection = null
        }
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
                .focusRequester(rowFocus)
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.tv_plugins_audio_priority_item, index + 1, plugin.manifest.name),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (index >
                    0
                ) {
                    TvButton(stringResource(R.string.tv_plugins_move_earlier), {
                        moveDirection = -1
                        onSelect { current ->
                            current.moveAudio(plugin.id, -1)
                        }
                    })
                }
                if (index <
                    ordered.lastIndex
                ) {
                    TvButton(stringResource(R.string.tv_plugins_move_later), {
                        moveDirection = 1
                        onSelect { current ->
                            current.moveAudio(plugin.id, 1)
                        }
                    })
                }
            }
        }
    }
    item(key = "audio-enabled-title") { TvSectionHeader(stringResource(R.string.tv_plugins_audio)) }
    items(plugins.filter { it.enabled }, key = { "audio-enabled-${it.id}" }) { plugin ->
        val matchesOthers = plugin.id in selection.audio
        TvToggleRow(
            label = stringResource(R.string.tv_plugins_audio_match_others, plugin.manifest.name),
            supportingText =
                stringResource(
                    if (matchesOthers) R.string.tv_plugins_audio_match_others_on else R.string.tv_plugins_audio_match_others_off,
                ),
            checked = matchesOthers,
            onCheckedChange = { enabled ->
                onSelect { current ->
                    current.copy(
                        audio =
                            if (enabled) {
                                (current.audio + plugin.id).distinct()
                            } else {
                                current.audio -
                                    plugin.id
                            },
                    )
                }
            },
        )
    }
    item(key = "audio-order-done") { TvButton(stringResource(R.string.tv_plugins_audio_order_done), onDone) }
}

internal fun ProviderSelection.moveAudio(
    id: String,
    direction: Int,
): ProviderSelection {
    if (direction != -1 && direction != 1) return this
    val order = audio.distinct().toMutableList()
    val from = order.indexOf(id)
    val to = from + direction
    if (from < 0 || to !in order.indices) return this
    Collections.swap(order, from, to)
    return copy(audio = order)
}
