package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvSidePanel
import io.github.aedev.flow.ui.tv.focus.tvAcceleratedDpad
import nl.neerdael.projectm.core.PresetFacets
import java.text.NumberFormat

private const val FEW_PRESETS = 20
private const val DIMMED_ALPHA = 0.4f

/**
 * Mood side panel: the presets the visualizer plays, narrowed by options that combine within a
 * group (any of) and across groups (all of). Each chip shows how many presets the mix would hold
 * if it were toggled; an option that would leave none is dimmed and skipped by the D-pad.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BoxScope.TvMusicMoodPanel(
    visible: Boolean,
    viewModel: TvVisualizerViewModel,
    onClose: () -> Unit,
) {
    val facets by viewModel.moodTable.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var skipped by remember { mutableStateOf(viewModel.engine.moods.skipped) }
    LaunchedEffect(visible) { if (visible) skipped = viewModel.refreshSkipped() }

    TvSidePanel(
        visible = visible && facets != null,
        title = stringResource(R.string.tv_music_moods),
        onClose = onClose,
    ) {
        val table = facets ?: return@TvSidePanel
        val selected = settings?.moods.orEmpty()
        val selection = remember(table, selected, skipped) { table.select(selected, skipped) }
        val exhausted = selected.isNotEmpty() && selection.poolSize() == 0
        LazyColumn(
            modifier = Modifier.fillMaxSize().tvAcceleratedDpad(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "summary") {
                MoodSummary(
                    selection.poolSize(),
                    viewModel.categoryPresetCount(settings?.musicCategory),
                    selected.isEmpty(),
                    exhausted,
                    viewModel::clearMoods,
                )
            }
            items(table.groups(), key = { it.id }) { group ->
                MoodGroup(group, selection, selected, viewModel::toggleMood)
            }
        }
    }
}

@Composable
private fun MoodSummary(
    pool: Int,
    collection: Int,
    none: Boolean,
    exhausted: Boolean,
    onClear: () -> Unit,
) {
    val count = if (none) collection else pool
    val format = NumberFormat.getIntegerInstance()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text =
                when {
                    exhausted -> stringResource(R.string.tv_music_moods_none_match)
                    none && collection <= 0 -> stringResource(R.string.tv_music_moods_all)
                    else -> pluralStringResource(R.plurals.tv_music_moods_pool, count, format.format(count))
                },
            style = MaterialTheme.typography.titleMedium,
        )
        if (!none && !exhausted && pool < FEW_PRESETS) {
            Text(
                text = stringResource(R.string.tv_music_moods_few),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!none) TvFilterChip(stringResource(R.string.tv_music_moods_clear), false, onClear, compact = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodGroup(
    group: PresetFacets.Group,
    selection: PresetFacets.Selection,
    selected: Set<String>,
    onToggle: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = moodGroupLabel(group.id)?.let { stringResource(it) } ?: group.label,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            group.options.forEach { option ->
                val chosen = option.id in selected
                val dimmed = selection.isDimmed(option.id)
                val count = NumberFormat.getIntegerInstance().format(selection.countIfToggled(option.id))
                TvFilterChip(
                    label =
                        stringResource(
                            R.string.tv_music_mood_chip,
                            moodOptionLabel(option.id)?.let { stringResource(it) } ?: option.label,
                            count,
                        ),
                    selected = chosen,
                    onClick = { onToggle(option.id) },
                    modifier =
                        Modifier
                            .alpha(if (dimmed) DIMMED_ALPHA else 1f)
                            .focusProperties { canFocus = !dimmed },
                    compact = true,
                )
            }
        }
    }
}
