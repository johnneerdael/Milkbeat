package io.github.aedev.flow.data.library.catalog

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import org.junit.Test

class LocalCatalogRefsTest {
    @Test fun everyRefRoundTripsThroughItsEntity() {
        val refs =
            listOf(
                LocalRef.Artist("Tag & Wandrach"),
                LocalRef.Release("bp:4438992"),
                LocalRef.Release("album:remixes|a,b|label: x"),
                LocalRef.Label(".defaultbox"),
                LocalRef.Year(2024),
                LocalRef.Playlist("nas|Sets/friday.m3u"),
                LocalRef.All(LocalSection.ARTISTS, null),
                LocalRef.All(LocalSection.RELEASES, "Melodic House & Techno"),
            )
        for (ref in refs) assertThat(LocalRef.parse(ref.entity)).isEqualTo(ref)
    }

    @Test fun pagesThatAreNotLocalAreNotClaimed() {
        assertThat(LocalRef.parse(EntityRef(EntityKind.ARTIST, "UC123"))).isNull()
        assertThat(LocalRef.parse(EntityRef(EntityKind.MIX, "local:all:NOPE"))).isNull()
        assertThat(LocalRef.parse(EntityRef(EntityKind.MIX, "local:year:abc"))).isNull()
    }
}
