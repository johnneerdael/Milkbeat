package io.github.aedev.flow.ui.tv.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.data.catalog.MusicSource
import io.github.aedev.flow.ui.tv.navigation.TvDestination
import io.github.aedev.flow.ui.tv.navigation.TvTab
import io.github.aedev.flow.ui.tv.navigation.railFocusTab
import io.github.aedev.flow.ui.tv.screens.music.TvMusicTabsState
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/** A rail entry's icon: a Material symbol, or a provider's monochrome logo, both tinted as rail content. */
@Immutable
sealed interface TvRailIcon {
    data class Symbol(
        val image: ImageVector,
    ) : TvRailIcon

    data class Logo(
        @param:DrawableRes val res: Int,
    ) : TvRailIcon
}

/** One rail entry; a [badged] entry carries a dot for something there that wants attention. */
@Immutable
data class TvRailItem(
    val tab: TvTab,
    val label: String,
    val icon: TvRailIcon,
    val badged: Boolean = false,
)

/** The music tabs (or the single Music tab while there are none), then the fixed destinations. */
@Composable
fun tvRailItems(
    music: TvMusicTabsState,
    badged: TvDestination?,
): List<TvRailItem> {
    val musicItems =
        if (music.tabs.isEmpty()) {
            listOf(TvRailItem(TvTab.Music(null), stringResource(R.string.nav_music), TvRailIcon.Symbol(Icons.Outlined.MusicNote)))
        } else {
            music.tabs.map { tab ->
                TvRailItem(
                    tab = TvTab.Music(tab.source),
                    label = tab.label ?: stringResource(R.string.local_library_title),
                    icon =
                        tab.iconRes?.let { TvRailIcon.Logo(it) }
                            ?: TvRailIcon.Symbol(if (tab.source == MusicSource.Local) Icons.Outlined.Folder else Icons.Outlined.MusicNote),
                )
            }
        }
    return musicItems +
        TvDestination.fixed.map { destination ->
            TvRailItem(
                tab = TvTab.Fixed(destination),
                label = stringResource(destination.labelRes),
                icon = TvRailIcon.Symbol(destination.icon),
                badged = destination == badged,
            )
        }
}

/**
 * Collapsible navigation rail: 72dp icon-only strip that expands with labels
 * while any rail item holds focus. It overlays the content (which is laid out
 * against the collapsed width) so expansion never reflows the screen.
 */
@Composable
fun TvNavRail(
    items: List<TvRailItem>,
    selected: TvTab?,
    onSelected: (TvTab) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    selectedFocusRequester: FocusRequester? = null,
    acceptsEnteringFocus: Boolean = true,
) {
    val dimens = LocalTvDimens.current
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(
        targetValue = if (expanded) dimens.railExpandedWidth else dimens.railCollapsedWidth,
        label = "tvRailWidth",
    )

    Surface(
        modifier =
            modifier
                .width(width)
                .fillMaxHeight()
                .onFocusChanged {
                    expanded = it.hasFocus
                    onFocusChanged(it.hasFocus)
                },
        color =
            if (expanded) {
                MaterialTheme.colorScheme.surfaceContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        tonalElevation = if (expanded) 2.dp else 0.dp,
    ) {
        Column(
            modifier =
                Modifier
                    .focusProperties {
                        @OptIn(ExperimentalComposeUiApi::class)
                        enter = {
                            when {
                                !acceptsEnteringFocus -> FocusRequester.Cancel
                                else -> selectedFocusRequester ?: FocusRequester.Default
                            }
                        }
                        @OptIn(ExperimentalComposeUiApi::class)
                        exit = { direction ->
                            if (direction == FocusDirection.Left) {
                                FocusRequester.Cancel
                            } else {
                                FocusRequester.Default
                            }
                        }
                    }.focusGroup()
                    .padding(
                        start = dimens.railEdgePadding,
                        end = 12.dp,
                        top = dimens.overscanVertical,
                        bottom = dimens.overscanVertical,
                    ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Centred on the rail icons' column, with the app name starting where their labels do.
                Image(
                    painter = painterResource(R.drawable.ic_milkbeat_logo),
                    contentDescription = null,
                    modifier = Modifier.padding(start = 5.dp).size(38.dp),
                )
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                ) {
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            val focusTab = railFocusTab(items.map { it.tab }, selected)
            items.forEach { item ->
                TvRailEntry(
                    item = item,
                    selected = item.tab == selected,
                    expanded = expanded,
                    onClick = { onSelected(item.tab) },
                    modifier =
                        if (item.tab == focusTab && selectedFocusRequester != null) {
                            Modifier.focusRequester(selectedFocusRequester)
                        } else {
                            Modifier
                        },
                )
            }
        }
    }
}

@Composable
private fun TvRailEntry(
    item: TvRailItem,
    selected: Boolean,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var focused by remember { mutableStateOf(false) }

    Surface(
        onClick = onClick,
        modifier =
            modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused },
        shape = CircleShape,
        color =
            when {
                focused -> MaterialTheme.colorScheme.primary
                selected -> MaterialTheme.colorScheme.secondaryContainer
                else -> Color.Transparent
            },
        contentColor =
            when {
                focused -> MaterialTheme.colorScheme.onPrimary
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BadgedBox(badge = { if (item.badged) Badge() }) {
                when (val icon = item.icon) {
                    is TvRailIcon.Symbol -> Icon(icon.image, contentDescription = item.label, modifier = Modifier.size(26.dp))
                    is TvRailIcon.Logo -> Icon(painterResource(icon.res), contentDescription = item.label, modifier = Modifier.size(26.dp))
                }
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
