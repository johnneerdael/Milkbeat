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
}
