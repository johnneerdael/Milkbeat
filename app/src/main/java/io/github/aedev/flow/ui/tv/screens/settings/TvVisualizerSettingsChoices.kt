package io.github.aedev.flow.ui.tv.screens.settings

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.aedev.flow.R
import io.github.aedev.flow.player.audio.visualizer.VisualizerMusicCategory
import io.github.aedev.flow.player.audio.visualizer.VisualizerSettings
import nl.neerdael.projectm.core.ProjectMJNI
import kotlin.math.abs

// Ahead of the player's own timing for audio stacks that report it late, behind it for the rare early one.
private val VISUALIZER_TIMING_OPTIONS_MS = listOf(-100, -50, -25, 0, 25, 50, 75, 100, 150, 200)

/** The visualizer settings that pick one of several values, each opened in a side panel. */
internal enum class VisualizerPicker {
    PRESET_DURATION,
    MUSIC_CATEGORY,
    TRANSITION_LENGTH,
    TRANSITION_STYLE,
    NATIVE_TRAILS,
    FRAME_RATE,
    DETAIL,
    TIMING,
}

internal class VisualizerChoice(
    val label: String,
    val supportingText: String?,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

internal class VisualizerChoices(
    val title: String,
    val choices: List<VisualizerChoice>,
) {
    val value: String get() = choices.firstOrNull { it.selected }?.label.orEmpty()
}

@Composable
internal fun visualizerChoices(
    picker: VisualizerPicker,
    settings: VisualizerSettings,
    timingOffsetMs: Int,
    viewModel: TvVisualizerSettingsViewModel,
): VisualizerChoices {
    val defaults = viewModel.defaults
    return when (picker) {
        VisualizerPicker.PRESET_DURATION -> {
            choices(
                title = stringResource(R.string.visualizer_preset_duration),
                values = VisualizerSettings.PRESET_SECONDS,
                current = settings.presetSeconds,
                default = defaults.presetSeconds,
                label = { stringResource(R.string.visualizer_seconds_value, it) },
                onSelect = { viewModel.update { setPresetSeconds(it) } },
            )
        }

        VisualizerPicker.MUSIC_CATEGORY -> {
            val categories = viewModel.musicCategories()
            choices(
                title = stringResource(R.string.visualizer_music_category),
                values = categories,
                // A category without presets plays All, as the engine falls back to it.
                current = settings.musicCategory.takeIf { it in categories } ?: VisualizerMusicCategory.ALL,
                default = defaults.musicCategory,
                label = { stringResource(musicCategoryLabel(it)) },
                onSelect = { viewModel.update { setMusicCategory(it) } },
            )
        }

        VisualizerPicker.TRANSITION_LENGTH -> {
            choices(
                title = stringResource(R.string.visualizer_transition_length),
                values = (0..VisualizerSettings.MAX_TRANSITION_SECONDS).toList(),
                current = settings.clampedTransitionSeconds,
                default = defaults.transitionSeconds,
                label = {
                    if (it ==
                        0
                    ) {
                        stringResource(R.string.visualizer_transition_instant)
                    } else {
                        stringResource(R.string.visualizer_seconds_value, it)
                    }
                },
                onSelect = { viewModel.update { setTransitionSeconds(it) } },
            )
        }

        VisualizerPicker.TRANSITION_STYLE -> {
            choices(
                title = stringResource(R.string.visualizer_transition_style),
                values = VisualizerSettings.TRANSITION_MODES,
                current = settings.transitionMode,
                default = null,
                label = { stringResource(transitionStyleLabel(it)) },
                supportingText = { stringResource(transitionStyleSubtitle(it)) },
                onSelect = { viewModel.update { setTransitionMode(it) } },
            )
        }

        VisualizerPicker.NATIVE_TRAILS -> {
            choices(
                title = stringResource(R.string.visualizer_native_trails),
                values = TRAIL_LABELS.indices.toList(),
                current = settings.clampedNativeTrails,
                default = 0,
                label = { stringResource(TRAIL_LABELS[it]) },
                onSelect = { viewModel.update { setNativeTrails(it) } },
            )
        }

        VisualizerPicker.FRAME_RATE -> {
            val frameRates = viewModel.frameRates
            choices(
                title = stringResource(R.string.visualizer_frame_rate),
                values = frameRates,
                current = frameRates.minBy { abs(it - settings.frameRateCap) },
                default = frameRates.minBy { abs(it - defaults.frameRateCap) },
                label = { stringResource(R.string.visualizer_frame_rate_value, it) },
                onSelect = { viewModel.update { setFrameRateCap(it) } },
            )
        }

        VisualizerPicker.DETAIL -> {
            choices(
                title = stringResource(R.string.visualizer_detail),
                values = DETAIL_LABELS.indices.toList(),
                current = settings.clampedMeshLevel,
                default = defaults.meshLevel,
                label = { stringResource(DETAIL_LABELS[it]) },
                onSelect = { viewModel.update { setMeshLevel(it) } },
            )
        }

        VisualizerPicker.TIMING -> {
            choices(
                title = stringResource(R.string.visualizer_timing),
                values = VISUALIZER_TIMING_OPTIONS_MS,
                current = timingOffsetMs,
                default = 0,
                label = { stringResource(R.string.visualizer_timing_value, it) },
                onSelect = { viewModel.update { setTimingOffsetMs(it) } },
            )
        }
    }
}

@Composable
private fun <T> choices(
    title: String,
    values: List<T>,
    current: T,
    default: T?,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    supportingText: @Composable (T) -> String? = { null },
): VisualizerChoices {
    val defaultText = stringResource(R.string.visualizer_option_default)
    return VisualizerChoices(
        title = title,
        choices =
            values.map { value ->
                VisualizerChoice(
                    label = label(value),
                    supportingText = supportingText(value) ?: defaultText.takeIf { value == default },
                    selected = value == current,
                    onSelect = { onSelect(value) },
                )
            },
    )
}

private val TRAIL_LABELS =
    listOf(
        R.string.visualizer_native_trails_standard,
        R.string.visualizer_native_trails_medium,
        R.string.visualizer_native_trails_high,
    )

private val DETAIL_LABELS =
    listOf(
        R.string.visualizer_detail_minimal,
        R.string.visualizer_detail_low,
        R.string.visualizer_detail_medium,
        R.string.visualizer_detail_high,
        R.string.visualizer_detail_ultra,
    )

@StringRes
private fun transitionStyleLabel(mode: Int): Int =
    when (mode) {
        ProjectMJNI.TRANSITION_LIGHTWEIGHT -> R.string.visualizer_transition_style_lightweight
        ProjectMJNI.TRANSITION_CLASSIC -> R.string.visualizer_transition_style_classic
        else -> R.string.visualizer_transition_style_auto
    }

@StringRes
private fun transitionStyleSubtitle(mode: Int): Int =
    when (mode) {
        ProjectMJNI.TRANSITION_LIGHTWEIGHT -> R.string.visualizer_transition_style_lightweight_subtitle
        ProjectMJNI.TRANSITION_CLASSIC -> R.string.visualizer_transition_style_classic_subtitle
        else -> R.string.visualizer_transition_style_auto_subtitle
    }

@StringRes
private fun musicCategoryLabel(id: String): Int =
    when (id) {
        "chill" -> R.string.visualizer_category_chill
        "normal" -> R.string.visualizer_category_normal
        "intense" -> R.string.visualizer_category_intense
        else -> R.string.visualizer_category_all
    }
