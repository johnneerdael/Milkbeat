package io.github.aedev.flow.ui.tv.catalog

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.github.aedev.flow.ui.tv.components.TvCard
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.Attribution
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef

/**
 * An artist's page header: the name, audience and buttons, with the artist's round portrait beside
 * them, all on the page background where they stay readable.
 */
@Composable
internal fun TvCatalogPortraitHeader(
    header: EntityHeader,
    modifier: Modifier = Modifier,
    actionsModifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit,
) {
    val dimens = LocalTvDimens.current
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.overscanHorizontal, vertical = dimens.overscanVertical),
        horizontalArrangement = Arrangement.spacedBy(dimens.rowSpacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = header.title, style = MaterialTheme.typography.displaySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            HeaderDetails(header)
            // Buttons keep their full labels: one that no longer fits beside the others moves to a new line.
            FlowRow(
                modifier = actionsModifier.padding(top = 8.dp).focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                content = actions,
            )
        }
        header.artwork?.let { artwork ->
            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
                AsyncImage(
                    model = artwork.url,
                    contentDescription = header.title,
                    modifier = Modifier.size(dimens.portraitSize),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

/** An album's or playlist's details: who made it, the cover, the title and facts, and its buttons. */
@Composable
internal fun TvCatalogCoverPane(
    header: EntityHeader,
    onOpen: (EntityRef) -> Unit,
    modifier: Modifier = Modifier,
    actionsModifier: Modifier = Modifier,
    status: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit,
) {
    val dimens = LocalTvDimens.current
    Column(
        modifier = modifier.width(dimens.coverPaneWidth).fillMaxHeight(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        header.attribution?.takeIf { it.entity?.kind == EntityKind.ARTIST }?.let { attribution ->
            TvAttribution(attribution, onOpen)
        }
        Surface(
            modifier = Modifier.weight(1f, fill = false),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            AsyncImage(
                model = header.artwork?.url,
                contentDescription = header.title,
                modifier =
                    Modifier
                        .sizeIn(
                            maxWidth = dimens.coverArtSize,
                            maxHeight = dimens.coverArtSize,
                        ).aspectRatio(1f, matchHeightConstraintsFirst = true),
                contentScale = ContentScale.Crop,
            )
        }
        Text(
            text = header.title,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        HeaderDetails(header, centered = true)
        status()
        Row(
            modifier = actionsModifier.padding(top = 8.dp).focusGroup(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = actions,
        )
    }
}

/** Who made a collection, with their avatar; it opens their page when there is one. */
@Composable
private fun TvAttribution(
    attribution: Attribution,
    onOpen: (EntityRef) -> Unit,
) {
    val dimens = LocalTvDimens.current
    val content: @Composable () -> Unit = {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            attribution.avatar?.let { avatar ->
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    AsyncImage(
                        model = avatar.url,
                        contentDescription = null,
                        modifier = Modifier.size(dimens.attributionAvatarSize),
                        contentScale = ContentScale.Crop,
                    )
                }
            }
            Text(text = attribution.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    val target = attribution.entity?.takeIf { it.kind == EntityKind.ARTIST }
    if (target == null) {
        content()
    } else {
        TvCard(onClick = { onOpen(target) }, shape = CircleShape) { content() }
    }
}

@Composable
private fun HeaderDetails(
    header: EntityHeader,
    centered: Boolean = false,
) {
    val align = if (centered) TextAlign.Center else TextAlign.Start
    header.details.forEach { line ->
        Text(
            text = line,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    header.description?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = align,
            maxLines = DESCRIPTION_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val DESCRIPTION_LINES = 3
