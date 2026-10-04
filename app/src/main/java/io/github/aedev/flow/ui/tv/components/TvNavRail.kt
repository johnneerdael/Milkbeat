package io.github.aedev.flow.ui.tv.components

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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.aedev.flow.R
import io.github.aedev.flow.ui.tv.navigation.TvDestination
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens

/**
 * Collapsible navigation rail: 72dp icon-only strip that expands with labels
 * while any rail item holds focus. It overlays the content (which is laid out
 * against the collapsed width) so expansion never reflows the screen. The
 * [badged] destination carries a dot for something there that wants attention.
 */
@Composable
fun TvNavRail(
    selected: TvDestination,
    onSelected: (TvDestination) -> Unit,
    onFocusChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    selectedFocusRequester: FocusRequester? = null,
    acceptsEnteringFocus: Boolean = true,
    badged: TvDestination? = null,
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
            TvDestination.primary.forEach { destination ->
                TvRailItem(
                    destination = destination,
                    selected = destination == selected,
                    expanded = expanded,
                    badged = destination == badged,
                    onClick = { onSelected(destination) },
                    modifier =
                        if (destination == selected && selectedFocusRequester != null) {
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
private fun TvRailItem(
    destination: TvDestination,
    selected: Boolean,
    expanded: Boolean,
    badged: Boolean,
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
            BadgedBox(badge = { if (badged) Badge() }) {
                Icon(
                    imageVector = destination.icon,
                    contentDescription = stringResource(destination.labelRes),
                    modifier = Modifier.size(26.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
            ) {
                Text(
                    text = stringResource(destination.labelRes),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
