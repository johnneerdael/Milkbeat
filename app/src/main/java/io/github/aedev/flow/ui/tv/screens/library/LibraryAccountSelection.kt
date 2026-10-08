package io.github.aedev.flow.ui.tv.screens.library

import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibrarySection

internal fun selectLibraryAccountSection(
    selected: TvAccountLibrarySection?,
    previousOwner: String?,
    currentOwner: String,
): TvAccountLibrarySection? =
    when {
        previousOwner != currentOwner || selected == null -> TvAccountLibrarySection.OVERVIEW
        else -> selected
    }

/**
 * The provider whose account pane the Library shows: none once a fixed section was picked, else the
 * chosen provider, falling back to the first one so signed-in listeners land on a provider library.
 */
internal fun <T> selectLibraryProvider(
    accounts: List<T>,
    providerId: (T) -> String,
    chosenProvider: String?,
    sectionSelected: Boolean,
): T? =
    if (sectionSelected) {
        null
    } else {
        accounts.firstOrNull { providerId(it) == chosenProvider } ?: accounts.firstOrNull()
    }
