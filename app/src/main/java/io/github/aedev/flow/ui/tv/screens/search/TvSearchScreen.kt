package io.github.aedev.flow.ui.tv.screens.search

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.R
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.data.local.SearchType
import io.github.aedev.flow.data.local.matching
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.components.TvSearchField
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.navigation.TvMusicTabsState
import io.github.aedev.flow.ui.tv.screens.TvRecentSearches
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.plugin.MetadataSurface

private const val SUGGESTION_CHIPS = 8
private const val RECENT_SUGGESTIONS = 3

/**
 * D-pad-first search: the query field, one chip per music tab that can search plus Videos, the
 * filters the chosen chip's plugin offers, and suggestion chips while typing; below them the recent
 * searches, or the plugin's result page rendered as any catalog page is.
 */
@Composable
fun TvSearchScreen(
    musicTabs: TvMusicTabsState,
    onVideoClick: (Video) -> Unit,
    onChannelClick: (String) -> Unit,
    onOpenPlaylist: (String) -> Unit,
    onPlayMix: (MusicTrack) -> Unit,
    onOpenCatalog: (EntityRef, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TvSearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val dimens = LocalTvDimens.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val recentSearches by viewModel.recentSearches.collectAsStateWithLifecycle()
    val musicChips = musicTabs.tabs.filter { it.source == MusicSource.Local || MetadataSurface.SEARCH in it.surfaces }
    val chips = musicChips.map { TvSearchSource.Music(it.source) } + TvSearchSource.Videos
    val startSource =
        (musicChips.firstOrNull { it.source == musicTabs.selected } ?: musicChips.firstOrNull())
            ?.let { TvSearchSource.Music(it.source) } ?: TvSearchSource.Videos
    // Not saveable on purpose: every visit to Search starts on the music tab last shown.
    // Unpicked, Search follows the start chip as the tabs settle; a picked chip that goes away gives way to it.
    var picked by remember { mutableStateOf<TvSearchSource?>(null) }
    val source = shownSearchSource(picked, chips, startSource)
    val chipIdentities = musicChips.associate { TvSearchSource.Music(it.source).key to it.identity } + (TvSearchSource.Videos.key to null)
    LaunchedEffect(chipIdentities) { viewModel.retainSources(chipIdentities) }
    LaunchedEffect(source) { viewModel.showSource(source) }
    val localLabel = stringResource(R.string.local_library_title)
    val videosLabel = stringResource(R.string.tv_filter_videos)
    val chipLabel: (TvSearchSource) -> String = { chip ->
        when (chip) {
            is TvSearchSource.Music -> musicChips.firstOrNull { it.source == chip.source }?.label ?: localLabel
            TvSearchSource.Videos -> videosLabel
        }
    }
    val shownSource by rememberUpdatedState(source)
    val openCatalog: (EntityRef) -> Unit = { entity ->
        (shownSource as? TvSearchSource.Music)?.let { onOpenCatalog(entity, it.source.providerId) }
    }

    val voiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data
                    ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    ?.firstOrNull()
                    ?.let { viewModel.submit(it, SearchType.VOICE) }
            }
        }
    val voiceIntent =
        remember {
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
        }
    val voiceAvailable = remember { voiceIntent.resolveActivity(context.packageManager) != null }

    val query = state.query
    val results = state.results(source)
    val suggestions =
        mergeSuggestions(
            recent = recentSearches.matching(query.trim(), RECENT_SUGGESTIONS).map { it.query },
            live = state.suggestions(source),
            limit = SUGGESTION_CHIPS,
        )
    // A live search runs on every pause in typing; only acting on a result saves the query.
    val currentQuery by rememberUpdatedState(query)
    val remembered = { viewModel.rememberSearch(currentQuery) }
    val actions =
        rememberTvSearchActions(
            viewModel = viewModel,
            blocks = results.blocks,
            beforeOpen = remembered,
            onVideoClick = onVideoClick,
            onChannelClick = onChannelClick,
            onOpenPlaylist = onOpenPlaylist,
            onPlayMix = onPlayMix,
            onOpenCatalog = openCatalog,
        )
    val chipPadding = PaddingValues(horizontal = dimens.overscanHorizontal)

    Column(
        modifier = modifier.fillMaxSize().padding(top = dimens.overscanVertical),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TvSearchField(
            query = query,
            onQueryChange = viewModel::onQueryChange,
            onSearch = remembered,
            onVoice = if (voiceAvailable) ({ voiceLauncher.launch(voiceIntent) }) else null,
            modifier = Modifier.fillMaxWidth().padding(chipPadding),
        )
        TvSearchChipRow(
            chips = chips,
            key = { it.key },
            label = { chipLabel(it) },
            selected = { it == source },
            onClick = { picked = it },
            contentPadding = chipPadding,
        )
        if (results.filters.isNotEmpty()) {
            TvSearchChipRow(
                chips = results.filters,
                key = { it.id },
                label = { it.label },
                selected = { it.id == results.filterId },
                onClick = { viewModel.selectFilter(it.id) },
                contentPadding = chipPadding,
            )
        }
        if (query.isNotBlank() && suggestions.isNotEmpty()) {
            TvSearchChipRow(
                chips = suggestions,
                key = { it },
                label = { it },
                selected = { false },
                onClick = { viewModel.submit(it, SearchType.SUGGESTION) },
                contentPadding = chipPadding,
            )
        }

        if (query.isBlank() && recentSearches.isNotEmpty()) {
            TvRecentSearches(
                history = recentSearches,
                onPick = viewModel::pick,
                onForget = viewModel::forgetSearch,
                onClear = viewModel::clearSearchHistory,
                modifier = Modifier.weight(1f).padding(chipPadding),
            )
        } else {
            // Each chip, query and filter scrolls from the top of its own list.
            key(source, results.query, results.filterId) {
                TvSearchResultsPane(
                    query = query,
                    source = source,
                    results = results,
                    actions = actions.of(source),
                    onLoadMore = { viewModel.loadMore(source) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun <T> TvSearchChipRow(
    chips: List<T>,
    key: (T) -> Any,
    label: @Composable (T) -> String,
    selected: (T) -> Boolean,
    onClick: (T) -> Unit,
    contentPadding: PaddingValues,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().tvRowFocus(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = contentPadding,
    ) {
        items(chips, key = key) { chip ->
            TvFilterChip(label = label(chip), selected = selected(chip), onClick = { onClick(chip) })
        }
    }
}
