package io.github.aedev.flow.ui.tv.screens.library

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.ui.tv.screens.account.TvAccountLibrarySection
import org.junit.Test

class LibraryAccountSelectionTest {
    @Test fun anAccountPaneFollowsItsOwnerAndKeepsItsCurrentSectionForTheSameOwner() {
        assertThat(selectLibraryAccountSection(null, null, "spotify:a")).isEqualTo(TvAccountLibrarySection.OVERVIEW)
        assertThat(selectLibraryAccountSection(TvAccountLibrarySection.RECENTLY_PLAYED, "spotify:a", "spotify:a"))
            .isEqualTo(TvAccountLibrarySection.RECENTLY_PLAYED)
        assertThat(selectLibraryAccountSection(TvAccountLibrarySection.RECENTLY_PLAYED, "spotify:a", "spotify:b"))
            .isEqualTo(TvAccountLibrarySection.OVERVIEW)
    }

    @Test fun aPickedSectionWinsOverAProviderChosenEarlier() {
        val accounts = listOf("beatport", "spotify")
        assertThat(selectLibraryProvider(accounts, { it }, chosenProvider = "spotify", sectionSelected = true)).isNull()
        assertThat(selectLibraryProvider(accounts, { it }, chosenProvider = null, sectionSelected = true)).isNull()
    }

    @Test fun withoutASectionTheChosenProviderShowsAndOtherwiseTheFirst() {
        val accounts = listOf("beatport", "spotify")
        assertThat(selectLibraryProvider(accounts, { it }, chosenProvider = "spotify", sectionSelected = false)).isEqualTo("spotify")
        assertThat(selectLibraryProvider(accounts, { it }, chosenProvider = null, sectionSelected = false)).isEqualTo("beatport")
        assertThat(selectLibraryProvider(accounts, { it }, chosenProvider = "soundcloud", sectionSelected = false)).isEqualTo("beatport")
        assertThat(selectLibraryProvider(emptyList<String>(), { it }, chosenProvider = null, sectionSelected = false)).isNull()
    }
}
