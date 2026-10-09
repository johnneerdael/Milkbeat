package io.github.aedev.flow.ui.tv.screens

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.ui.tv.components.TvScreenScaffold
import io.github.aedev.flow.ui.tv.focus.tvInitialFocus
import io.github.aedev.flow.ui.tv.screens.folders.TvMusicFoldersSettingsPane
import io.github.aedev.flow.ui.tv.screens.settings.TvAboutSettingsPane
import io.github.aedev.flow.ui.tv.screens.settings.TvPlaybackSettingsPane
import io.github.aedev.flow.ui.tv.screens.settings.TvPluginsSettingsPane
import io.github.aedev.flow.ui.tv.screens.settings.TvSettingsCategory
import io.github.aedev.flow.ui.tv.screens.settings.TvVisualizerSettingsPane
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/**
 * Two-pane TV settings: focusable category list on the left, the selected
 * category's pane on the right; the pane follows focus.
 */
@Composable
fun TvSettingsScreen(
    modifier: Modifier = Modifier,
    onOpenPluginSignIn: (pluginId: String, methodId: String) -> Unit = { _, _ -> },
    initialCategory: TvSettingsCategory = TvSettingsCategory.PLUGINS,
    initiallyFocusPane: Boolean = false,
) {
    val context = LocalContext.current
    val playerPreferences = remember { PlayerPreferences(context.applicationContext) }
    var selectedCategory by rememberSaveable { mutableStateOf(initialCategory) }
    var paneFocusRequests by remember { mutableIntStateOf(0) }
    var pluginHomeRevision by rememberSaveable { mutableIntStateOf(0) }
    val categoryFocus = remember { TvSettingsCategory.entries.associateWith { FocusRequester() } }
    val dimens = LocalTvDimens.current

    TvScreenScaffold(
        title = null,
        modifier = modifier,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimens.overscanHorizontal),
            horizontalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            Column(
                modifier =
                    Modifier
                        .width(300.dp)
                        .verticalScroll(rememberScrollState())
                        // Entering from the rail lands on the open category, not whichever row is level with it.
                        .focusProperties {
                            @OptIn(ExperimentalComposeUiApi::class)
                            enter = { categoryFocus.getValue(selectedCategory) }
                        }.focusGroup(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                TvSettingsCategory.entries.forEach { category ->
                    TvSettingsCategoryItem(
                        category = category,
                        selected = category == selectedCategory,
                        focusRequester = categoryFocus.getValue(category),
                        onSelect = { selectedCategory = category },
                        onActivate = {
                            selectedCategory = category
                            paneFocusRequests++
                            if (category == TvSettingsCategory.PLUGINS) pluginHomeRevision++
                        },
                    )
                }
            }

            androidx.compose.foundation.layout.Box(
                modifier = Modifier.weight(1f).tvInitialFocus(paneFocusRequests, onFirstComposition = initiallyFocusPane).focusGroup(),
            ) {
                when (selectedCategory) {
                    TvSettingsCategory.PLUGINS -> {
                        TvPluginsSettingsPane(
                            onSignIn = onOpenPluginSignIn,
                            onOpenAppUpdates = {
                                selectedCategory = TvSettingsCategory.ABOUT
                                paneFocusRequests++
                            },
                            homeRevision = pluginHomeRevision,
                        )
                    }

                    TvSettingsCategory.MUSIC_FOLDERS -> {
                        TvMusicFoldersSettingsPane()
                    }

                    TvSettingsCategory.PLAYBACK -> {
                        TvPlaybackSettingsPane(playerPreferences)
                    }

                    TvSettingsCategory.VISUALIZATIONS -> {
                        TvVisualizerSettingsPane()
                    }

                    TvSettingsCategory.ABOUT -> {
                        TvAboutSettingsPane()
                    }
                }
            }
        }
    }
}

@Composable
private fun TvSettingsCategoryItem(
    category: TvSettingsCategory,
    selected: Boolean,
    focusRequester: FocusRequester,
    onSelect: () -> Unit,
    onActivate: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = onActivate,
        modifier =
            Modifier.focusRequester(focusRequester).onFocusChanged { state ->
                focused = state.isFocused
                if (state.isFocused) onSelect()
            },
        shape = MaterialTheme.shapes.medium,
        color =
            when {
                focused -> MaterialTheme.colorScheme.primary
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> MaterialTheme.colorScheme.surfaceContainer
            },
        contentColor =
            when {
                focused -> MaterialTheme.colorScheme.onPrimary
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurface
            },
    ) {
        Row(
            modifier =
                Modifier
                    .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(category.icon, contentDescription = null)
            Text(
                text = stringResource(category.labelRes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
