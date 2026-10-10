package io.github.aedev.flow.ui.tv.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.local.AudioQualityReadoutPreferences
import io.github.aedev.flow.data.local.DebugLoggingPreferences
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.ui.tv.components.TvToggleRow
import kotlinx.coroutines.launch

@Composable
fun TvPlaybackSettingsPane(
    playerPreferences: PlayerPreferences,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val debugPreferences = remember(context) { DebugLoggingPreferences(context) }
    val debugLogging by debugPreferences.enabled.collectAsStateWithLifecycle(initialValue = false)
    val qualityPreferences = remember(context) { AudioQualityReadoutPreferences(context) }
    val audioQuality by qualityPreferences.enabled.collectAsStateWithLifecycle(initialValue = false)
    val backgroundPlay by playerPreferences.backgroundPlayEnabled.collectAsStateWithLifecycle(initialValue = true)
    val autoplay by playerPreferences.autoplayEnabled.collectAsStateWithLifecycle(initialValue = true)
    val skipSilence by playerPreferences.skipSilenceEnabled.collectAsStateWithLifecycle(initialValue = false)
    val stableVolume by playerPreferences.stableVolumeEnabled.collectAsStateWithLifecycle(initialValue = false)
    val subtitles by playerPreferences.subtitlesEnabled.collectAsStateWithLifecycle(initialValue = false)
    val ambientMode by playerPreferences.videoAmbientModeEnabled.collectAsStateWithLifecycle(initialValue = false)

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "background-play") {
            TvToggleRow(
                label = stringResource(R.string.player_settings_background_play),
                supportingText = stringResource(R.string.player_settings_background_play_subtitle),
                checked = backgroundPlay,
                onCheckedChange = { scope.launch { playerPreferences.setBackgroundPlayEnabled(it) } },
            )
        }
        item(key = "autoplay") {
            TvToggleRow(
                label = stringResource(R.string.player_settings_autoplay),
                supportingText = stringResource(R.string.player_settings_autoplay_subtitle),
                checked = autoplay,
                onCheckedChange = { scope.launch { playerPreferences.setAutoplayEnabled(it) } },
            )
        }
        item(key = "subtitles") {
            TvToggleRow(
                label = stringResource(R.string.filter_subtitles),
                checked = subtitles,
                onCheckedChange = { scope.launch { playerPreferences.setSubtitlesEnabled(it) } },
            )
        }
        item(key = "skip-silence") {
            TvToggleRow(
                label = stringResource(R.string.player_settings_skip_silence),
                supportingText = stringResource(R.string.player_settings_skip_silence_subtitle),
                checked = skipSilence,
                onCheckedChange = { scope.launch { playerPreferences.setSkipSilenceEnabled(it) } },
            )
        }
        item(key = "stable-volume") {
            TvToggleRow(
                label = stringResource(R.string.player_settings_stable_voice),
                supportingText = stringResource(R.string.player_settings_stable_voice_subtitle),
                checked = stableVolume,
                onCheckedChange = { scope.launch { playerPreferences.setStableVolumeEnabled(it) } },
            )
        }
        item(key = "ambient") {
            TvToggleRow(
                label = stringResource(R.string.player_settings_ambient_mode),
                supportingText = stringResource(R.string.player_settings_ambient_mode_subtitle),
                checked = ambientMode,
                onCheckedChange = { scope.launch { playerPreferences.setVideoAmbientModeEnabled(it) } },
            )
        }
        item(key = "audio-quality") {
            TvToggleRow(
                label = stringResource(R.string.player_settings_audio_quality),
                supportingText = stringResource(R.string.player_settings_audio_quality_subtitle),
                checked = audioQuality,
                onCheckedChange = { scope.launch { qualityPreferences.setEnabled(it) } },
            )
        }
        item(key = "debug-logging") {
            TvToggleRow(
                label = stringResource(R.string.player_settings_debug_logging),
                supportingText = stringResource(R.string.player_settings_debug_logging_subtitle),
                checked = debugLogging,
                onCheckedChange = { scope.launch { debugPreferences.setEnabled(it) } },
            )
        }
    }
}
