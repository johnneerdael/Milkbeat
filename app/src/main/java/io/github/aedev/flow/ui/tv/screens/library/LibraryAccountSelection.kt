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
