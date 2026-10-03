package io.github.aedev.flow.ui.tv.screens.settings

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.VisualizerPreferences
import io.github.aedev.flow.player.audio.visualizer.VisualizerSettings
import io.github.aedev.flow.ui.tv.components.TvNavRow
import io.github.aedev.flow.ui.tv.components.TvSectionHeader
import io.github.aedev.flow.ui.tv.components.TvSelectionRow
import io.github.aedev.flow.ui.tv.components.TvSidePanel
import io.github.aedev.flow.ui.tv.components.TvToggleRow

@Composable
fun TvVisualizerSettingsPane(
    modifier: Modifier = Modifier,
    viewModel: TvVisualizerSettingsViewModel = hiltViewModel(),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val timingOffsetMs by viewModel.timingOffsetMs.collectAsStateWithLifecycle()
    val showMusicVideos by viewModel.showMusicVideos.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val skippedPresets by viewModel.skippedPresets.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<VisualizerPicker?>(null) }
    var shownPicker by remember { mutableStateOf<VisualizerPicker?>(null) }
    val rowFocus = remember { VisualizerPicker.entries.associateWith { FocusRequester() } }
    val engineSettings = settings?.takeIf { enabled && viewModel.supported }

    LaunchedEffect(Unit) { viewModel.refreshSkippedPresets() }
    // The panel keeps focus until it has closed, so the row that opened it takes focus back a frame later.
    LaunchedEffect(picker) {
        if (picker != null) return@LaunchedEffect
        val returnTo = shownPicker ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { rowFocus.getValue(returnTo).requestFocus() }
    }
    BackHandler(enabled = picker != null) { picker = null }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "visualizer-enabled") {
                TvToggleRow(
                    label = stringResource(R.string.visualizer_enabled),
                    supportingText =
                        stringResource(
                            if (viewModel.supported) R.string.visualizer_enabled_subtitle else R.string.visualizer_unavailable,
                        ),
                    checked = enabled && viewModel.supported,
                    onCheckedChange = { if (viewModel.supported) viewModel.setEnabled(it) },
                )
            }
            item(key = "visualizer-music-videos") {
                TvToggleRow(
                    label = stringResource(R.string.visualizer_music_videos),
                    supportingText = stringResource(R.string.visualizer_music_videos_subtitle),
                    checked = showMusicVideos,
                    onCheckedChange = viewModel::setShowMusicVideos,
                )
            }
            if (engineSettings != null) {
                engineSettingsItems(
                    settings = engineSettings,
                    timingOffsetMs = timingOffsetMs,
                    diagnostics = diagnostics,
                    skippedPresets = skippedPresets,
                    viewModel = viewModel,
                    rowFocus = rowFocus,
                    onOpen = {
                        shownPicker = it
                        picker = it
                    },
                )
            }
        }

        val shown = shownPicker
        TvSidePanel(
            visible = picker != null && engineSettings != null,
            title =
                if (shown != null &&
                    engineSettings != null
                ) {
                    visualizerChoices(shown, engineSettings, timingOffsetMs, viewModel).title
                } else {
                    ""
                },
            onClose = { picker = null },
            initialContentFocus = false,
        ) {
            if (shown != null && engineSettings != null) {
                key(shown) {
                    VisualizerChoiceList(
                        choices = visualizerChoices(shown, engineSettings, timingOffsetMs, viewModel),
                        onChosen = { picker = null },
                    )
                }
            }
        }
    }
}

private fun LazyListScope.engineSettingsItems(
    settings: VisualizerSettings,
    timingOffsetMs: Int,
    diagnostics: Boolean,
    skippedPresets: Int,
    viewModel: TvVisualizerSettingsViewModel,
    rowFocus: Map<VisualizerPicker, FocusRequester>,
    onOpen: (VisualizerPicker) -> Unit,
) {
    fun pickerRow(
        picker: VisualizerPicker,
        @StringRes subtitle: Int? = null,
        @StringRes label: Int? = null,
    ) = item(key = "visualizer-picker-$picker") {
        val choices = visualizerChoices(picker, settings, timingOffsetMs, viewModel)
        TvNavRow(
            label = label?.let { stringResource(it) } ?: choices.title,
            value = choices.value,
            supportingText = subtitle?.let { stringResource(it) },
            onClick = { onOpen(picker) },
            modifier = Modifier.focusRequester(rowFocus.getValue(picker)),
        )
    }

    fun toggleRow(
        key: String,
        @StringRes label: Int,
        checked: Boolean,
        subtitle: @Composable () -> String,
        onChange: suspend VisualizerPreferences.(Boolean) -> Unit,
    ) = item(key = "visualizer-$key") {
        TvToggleRow(
            label = stringResource(label),
            supportingText = subtitle(),
            checked = checked,
            onCheckedChange = { value -> viewModel.update { onChange(value) } },
        )
    }

    sectionHeader("presets", R.string.visualizer_section_presets)
    toggleRow("auto-change", R.string.visualizer_auto_change, settings.autoChange, {
        stringResource(R.string.visualizer_auto_change_subtitle)
    }) {
        setAutoChange(it)
    }
    pickerRow(VisualizerPicker.PRESET_DURATION)
    pickerRow(VisualizerPicker.MUSIC_CATEGORY, R.string.visualizer_music_category_subtitle)
    toggleRow("beat-cuts", R.string.visualizer_beat_cuts, settings.beatCuts, { stringResource(R.string.visualizer_beat_cuts_subtitle) }) {
        setBeatCuts(it)
    }
    toggleRow("skip-blank", R.string.visualizer_skip_blank, settings.blankDetection, {
        stringResource(R.string.visualizer_skip_blank_subtitle)
    }) {
        setBlankDetection(it)
    }
    toggleRow("skip-slow", R.string.visualizer_skip_slow, settings.skipSlowPresets, {
        stringResource(R.string.visualizer_skip_slow_subtitle)
    }) {
        setSkipSlowPresets(it)
    }
    item(key = "visualizer-skipped") {
        TvSelectionRow(
            label = stringResource(R.string.visualizer_skipped_reset),
            supportingText =
                if (skippedPresets > 0) {
                    pluralStringResource(R.plurals.visualizer_skipped_count, skippedPresets, skippedPresets)
                } else {
                    stringResource(R.string.visualizer_skipped_none)
                },
            selected = false,
            onClick = viewModel::resetSkippedPresets,
        )
    }

    sectionHeader("transitions", R.string.visualizer_section_transitions)
    pickerRow(VisualizerPicker.TRANSITION_LENGTH)
    pickerRow(VisualizerPicker.TRANSITION_STYLE)

    sectionHeader("quality", R.string.visualizer_section_quality)
    pickerRow(VisualizerPicker.RESOLUTION)
    pickerRow(VisualizerPicker.FRAME_RATE, R.string.visualizer_frame_rate_subtitle)
    pickerRow(VisualizerPicker.DETAIL, R.string.visualizer_detail_subtitle)
    toggleRow("memory-limit", R.string.visualizer_memory_limit, settings.memoryLimit, {
        val height = viewModel.memoryLimitHeight
        if (height > 0) {
            stringResource(R.string.visualizer_memory_limit_subtitle, heightLabel(height))
        } else {
            stringResource(R.string.visualizer_memory_limit_none)
        }
    }) { setMemoryLimit(it) }
    toggleRow("diagnostics", R.string.visualizer_diagnostics, diagnostics, { stringResource(R.string.visualizer_diagnostics_subtitle) }) {
        setDiagnostics(it)
    }

    sectionHeader("timing", R.string.visualizer_timing)
    item(key = "visualizer-timing-hint") {
        Text(
            text = stringResource(R.string.visualizer_timing_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    pickerRow(VisualizerPicker.TIMING, label = R.string.visualizer_timing_offset)
}

private fun LazyListScope.sectionHeader(
    key: String,
    @StringRes title: Int,
) = item(key = "visualizer-section-$key") {
    TvSectionHeader(title = stringResource(title), modifier = Modifier.padding(top = 12.dp))
}

/** The values of one setting; focus starts on the one in use, and choosing one closes the panel. */
@Composable
private fun VisualizerChoiceList(
    choices: VisualizerChoices,
    onChosen: () -> Unit,
) {
    val selectedIndex = choices.choices.indexOfFirst { it.selected }.coerceAtLeast(0)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex)
    val selectedFocus = remember { FocusRequester() }
    LaunchedEffect(choices.title) {
        withFrameNanos { }
        runCatching { selectedFocus.requestFocus() }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(choices.choices, key = { index, _ -> index }) { index, choice ->
            TvSelectionRow(
                label = choice.label,
                supportingText = choice.supportingText,
                selected = choice.selected,
                onClick = {
                    choice.onSelect()
                    onChosen()
                },
                modifier = if (index == selectedIndex) Modifier.focusRequester(selectedFocus) else Modifier,
            )
        }
    }
}
