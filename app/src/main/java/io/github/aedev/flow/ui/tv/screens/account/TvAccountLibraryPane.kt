package io.github.aedev.flow.ui.tv.screens.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.aedev.flow.plugin.catalog.ProviderEntityReference
import io.github.aedev.flow.ui.tv.components.TvFilterChip
import io.github.aedev.flow.ui.tv.focus.tvRowFocus
import io.github.aedev.flow.ui.tv.screens.library.selectLibraryAccountSection
import io.github.aedev.flow.ui.tv.theme.LocalTvDimens
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef

/**
 * One plugin's account library inside Library: its section chips, then the chosen section. Another
 * account of the same plugin starts again on the overview.
 */
@Composable
internal fun TvAccountLibraryPane(
    pluginId: String,
    callbacks: TvAccountLibraryCallbacks,
    modifier: Modifier = Modifier,
) {
    val viewModel =
        hiltViewModel<TvAccountLibraryViewModel, TvAccountLibraryViewModel.Factory>(
            key = "account-library:$pluginId",
            creationCallback = { factory -> factory.create(pluginId) },
        )
    val identity by viewModel.accountIdentity.collectAsStateWithLifecycle(initialValue = "")
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    var section by rememberSaveable(pluginId) { mutableStateOf<TvAccountLibrarySection?>(null) }
    var owner by rememberSaveable(pluginId) { mutableStateOf<String?>(null) }
    LaunchedEffect(identity) {
        if (identity.isEmpty()) return@LaunchedEffect
        viewModel.accountChanged(identity)
        section = selectLibraryAccountSection(section, owner, identity)
        owner = identity
        if (section == TvAccountLibrarySection.OVERVIEW) viewModel.open(TvAccountLibrarySection.OVERVIEW)
    }

    // The section's stations and playlists seed their radio from this provider, whichever tab was last shown.
    val seeded =
        remember(callbacks, pluginId) {
            TvAccountLibraryCallbacks(
                onVideoClick = callbacks.onVideoClick,
                onOpenPlaylist = callbacks.onOpenPlaylist,
                onPlayMix = callbacks.onPlayMix,
                onPlayCollection = { track, queue, source, radioPlaylistId ->
                    callbacks.onPlayCollection(
                        track,
                        queue,
                        source,
                        radioPlaylistId?.let { ProviderEntityReference.encode(pluginId, EntityRef(EntityKind.PLAYLIST, it)) },
                    )
                },
                onOpenCatalog = callbacks.onOpenCatalog,
            )
        }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LazyRow(
            modifier = Modifier.fillMaxWidth().tvRowFocus(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(horizontal = LocalTvDimens.current.overscanHorizontal),
        ) {
            items(libraryNavigationTabs(tabs), key = { it.section.name }) { tab ->
                TvFilterChip(
                    label = tab.label ?: stringResource(tab.section.titleRes),
                    selected = section == tab.section,
                    onClick = { section = tab.section },
                    compact = true,
                )
            }
        }
        val shown = section?.takeIf { current -> owner == identity && tabs.any { it.section == current } }
        if (shown != null) TvAccountLibraryContent(section = shown, viewModel = viewModel, callbacks = seeded)
    }
}
