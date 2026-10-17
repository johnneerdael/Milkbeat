package io.github.aedev.flow.ui.tv.music

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.plugin.playback.RadioTuningState
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvMusicTrackRow
import io.github.aedev.flow.ui.tv.components.TvSidePanel
import io.github.aedev.flow.ui.tv.focus.tvAcceleratedDpad
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import kotlinx.coroutines.launch

private const val QUEUE_PANEL_ALPHA = 0.5f

// Rows sit a little more solid than the panel so they stay readable while the visual shows through.
private const val QUEUE_ROW_ALPHA = 0.6f

/** Music queue side panel: current queue plus the automix (radio) continuation; picking either plays it now. */
@Composable
fun BoxScope.TvMusicQueuePanel(
    visible: Boolean,
    manager: EnhancedMusicPlayerManager,
    onPlayRadioTrack: (MusicTrack) -> Unit,
    onClose: () -> Unit,
    tuning: io.github.aedev.flow.plugin.playback.RadioTuningState =
        io.github.aedev.flow.plugin.playback
            .RadioTuningState(),
    onTune: (String) -> Unit = {},
) {
    val dimens = LocalTvDimens.current
    val focusPadding = dimens.trackRowHeight * ((dimens.focusScale - 1f) / 2f) + dimens.focusBorderWidth
    val queue by manager.queue.collectAsStateWithLifecycle()
    val automix by manager.automixItems.collectAsStateWithLifecycle()
    val currentIndex by manager.currentQueueIndex.collectAsStateWithLifecycle()

    val openingIndex = remember(visible) { currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0)) }
    val listState = rememberLazyListState()
    val openingFocus = remember { FocusRequester() }
    val listFocus = remember { FocusRequester() }
    val presetFocus = remember { FocusRequester() }
    val rowFocusModifier =
        Modifier.focusProperties {
            if (tuning.choices.isNotEmpty()) right = presetFocus
        }
    LaunchedEffect(visible, queue.isNotEmpty()) {
        if (visible && queue.isNotEmpty()) {
            listState.scrollToItem(openingIndex)
            withFrameNanos { }
            openingFocus.requestFocus()
        }
    }

    TvSidePanel(
        visible = visible,
        title = stringResource(R.string.tv_player_queue),
        onClose = onClose,
        initialContentFocus = queue.isEmpty(),
        showHeader = false,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = QUEUE_PANEL_ALPHA),
    ) {
        if (queue.isEmpty() && automix.isEmpty()) {
            Text(
                text = stringResource(R.string.tv_library_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@TvSidePanel
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvRadioFilterControls(tuning, onTune, presetFocus, listFocus)
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(vertical = focusPadding),
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .tvAcceleratedDpad()
                        .focusRequester(listFocus)
                        .focusRestorer(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(queue, key = { index, item -> "queue:$index:${item.videoId}" }) { index, item ->
                    TvMusicTrackRow(
                        track = item,
                        selected = index == currentIndex,
                        modifier =
                            rowFocusModifier.then(if (index == openingIndex) Modifier.focusRequester(openingFocus) else Modifier),
                        onClick = { manager.playFromQueue(index) },
                        containerAlpha = QUEUE_ROW_ALPHA,
                    )
                }
                if (automix.isNotEmpty()) {
                    item(key = "automix-header") {
                        Text(
                            text = stringResource(R.string.tv_music_radio),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(automix, key = { index, item -> "automix:$index:${item.videoId}" }) { _, item ->
                        TvMusicTrackRow(
                            track = item,
                            modifier = rowFocusModifier,
                            onClick = { onPlayRadioTrack(item) },
                            containerAlpha = QUEUE_ROW_ALPHA,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TvRadioFilterControls(
    state: RadioTuningState,
    onSelect: (String) -> Unit,
    presetFocus: FocusRequester,
    listFocus: FocusRequester,
) {
    if (state.choices.isEmpty()) return
    val entryIndex = state.choices.indexOfFirst { it.id == state.selectedId }.coerceAtLeast(0)
    val rowState = rememberLazyListState(initialFirstVisibleItemIndex = entryIndex)
    val scope = rememberCoroutineScope()
    // Right always enters the selected preset, so it is scrolled back into the row whenever focus
    // leaves it: a chip scrolled out of the row is not composed and cannot take focus.
    LaunchedEffect(entryIndex) { rowState.scrollToItem(entryIndex) }
    LazyRow(
        state = rowState,
        modifier =
            Modifier
                .fillMaxWidth()
                .onFocusChanged { if (!it.hasFocus) scope.launch { rowState.scrollToItem(entryIndex) } },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(state.choices, key = { _, option -> option.id }) { index, option ->
            TvFilterChip(
                option.label,
                option.id == state.selectedId,
                { if (!state.loading) onSelect(option.id) },
                modifier =
                    Modifier
                        .then(if (index == entryIndex) Modifier.focusRequester(presetFocus) else Modifier)
                        .focusProperties { down = listFocus },
                compact = true,
            )
        }
    }
}
