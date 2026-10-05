package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.Test

class TrackMatchRecordingIdentityTest {
    private fun track(
        title: String,
        seconds: Long,
        vararg artists: String,
    ) = TrackDescriptor(
        ref = EntityRef(EntityKind.TRACK, title),
        title = title,
        artists = artists.map { ArtistCredit(it) },
        durationMs = seconds * 1000,
    )

    @Test
    fun `a named remixer credit may move from the artist list to the identical remix title`() {
        val source = track("Release The Pressure - Rebūke Remix", 170, "Calvin Harris", "Kasabian", "Rebūke")
        val candidate = track("Release The Pressure (Rebūke Remix)", 171, "Calvin Harris", "Kasabian")
        assertThat(TrackMatchScore.best(source, listOf(candidate))?.candidate).isEqualTo(candidate)
    }

    @Test
    fun `an extended version of the same named remix is acceptable`() {
        val source = track("When It Kicks - Rebūke Remix", 212, "Layton Giordani", "Green Velvet", "Rebūke")
        val candidate = track("When It Kicks (Rebūke Extended Remix)", 285, "Layton Giordani", "Green Velvet")
        assertThat(TrackMatchScore.best(source, listOf(candidate))?.candidate).isEqualTo(candidate)
    }

    @Test
    fun `a longer full named edit may replace its mixed excerpt with equivalent collective credits`() {
        val source = track("Nocturnal (Rebūke Edit) - Mixed", 207, "Cox and Coe", "Carl Cox", "Christopher Coe", "Rebūke")
        val candidate = track("Nocturnal (Rebūke Edit)", 388, "Carl Cox", "Christopher Coe")
        assertThat(TrackMatchScore.best(source, listOf(candidate))?.candidate).isEqualTo(candidate)
    }

    @Test
    fun `collective credits match the explicitly credited members without requiring the group alias`() {
        val source = track("Cluster - Rebuke Edit", 325, "Cox and Coe", "Carl Cox", "Christopher Coe", "Rebūke")
        val candidate = track("Cluster (Rebuke Edit)", 325, "Carl Cox", "Christopher Coe")
        assertThat(TrackMatchScore.best(source, listOf(candidate))?.candidate).isEqualTo(candidate)
    }

    @Test
    fun `collective performers match even when the destination credits them in the opposite order`() {
        val source = track("Cluster - Rebuke Edit", 325, "Cox and Coe", "Carl Cox", "Christopher Coe", "Rebūke")
        val candidate = track("Cluster (Rebuke Edit)", 325, "Christopher Coe", "Carl Cox")
        assertThat(TrackMatchScore.best(source, listOf(candidate))?.candidate).isEqualTo(candidate)
        assertThat(TrackMatchScore.best(candidate, listOf(source))?.candidate).isEqualTo(source)
    }

    @Test
    fun `a collective cannot match a different primary performer featuring its members`() {
        val source = track("Cluster - Rebuke Edit", 325, "Cox and Coe", "Carl Cox", "Christopher Coe", "Rebūke")
        val candidate = track("Cluster (Rebuke Edit)", 325, "Different Primary", "Christopher Coe", "Carl Cox")
        assertThat(TrackMatchScore.best(source, listOf(candidate))).isNull()
    }

    @Test
    fun `any duration increase is allowed when the title performers and named edit agree`() {
        val source = track("Nocturnal (Rebūke Edit)", 207, "Carl Cox", "Christopher Coe")
        val full = track("Nocturnal (Rebūke Edit)", 3600, "Carl Cox", "Christopher Coe")
        assertThat(TrackMatchScore.best(source, listOf(full))?.candidate).isEqualTo(full)
    }

    @Test
    fun `explicit full length versions are permitted without an arbitrary duration ratio limit`() {
        val source = track("Together (Radio Edit)", 180, "Primary")
        val full = track("Together (Full Length Version)", 1200, "Primary")
        assertThat(TrackMatchScore.best(source, listOf(full))?.candidate).isEqualTo(full)
    }

    @Test
    fun `a longer extended original may replace the short original`() {
        val source = track("I'm Just Calling", 234, "Claude VonStroke", "Rebūke")
        val candidate = track("I'm Just Calling (Extended Mix)", 380, "Claude VonStroke", "Rebūke")
        assertThat(TrackMatchScore.best(source, listOf(candidate))?.candidate).isEqualTo(candidate)
    }

    @Test
    fun `extended allowance preserves named remix performer and song identity`() {
        val source = track("Nocturnal (Rebūke Edit) - Mixed", 207, "Cox and Coe", "Carl Cox", "Christopher Coe", "Rebūke")
        for (candidate in listOf(
            track("Nocturnal (Another DJ Edit)", 388, "Carl Cox", "Christopher Coe"),
            track("Nocturnal (Rebūke Edit)", 388, "Another Artist", "Christopher Coe"),
            track("Another Song (Rebūke Edit)", 388, "Carl Cox", "Christopher Coe"),
            track("Nocturnal (Rebūke Edit)", 120, "Carl Cox", "Christopher Coe"),
            track("Nocturnal (Rebūke Edit) (Live)", 388, "Carl Cox", "Christopher Coe"),
        )) {
            assertThat(TrackMatchScore.best(source, listOf(candidate))).isNull()
        }
    }

    @Test
    fun `full edit is preferred even when the mixed excerpt appears first and has a higher duration score`() {
        val source = track("Nocturnal (Rebūke Edit) - Mixed", 207, "Carl Cox", "Christopher Coe")
        val full = track("Nocturnal (Rebūke Edit)", 388, "Carl Cox", "Christopher Coe")
        assertThat(TrackMatchScore.best(source, listOf(source, full))?.candidate).isEqualTo(full)
        assertThat(TrackMatchScore.best(source, listOf(full, source))?.candidate).isEqualTo(full)
    }

    @Test
    fun `a mixed excerpt ISRC does not override a valid full version`() {
        val source = track("Nocturnal (Rebūke Edit) - Mixed", 207, "Carl Cox", "Christopher Coe").copy(ids = mapOf("isrc" to "EXCERPT"))
        val full = track("Nocturnal (Rebūke Edit)", 388, "Carl Cox", "Christopher Coe")
        assertThat(TrackMatchScore.best(source, listOf(source, full))?.candidate).isEqualTo(full)
    }

    @Test
    fun `artist prefix in a video upload title preserves the named remix identity`() {
        val source = track("Thoughts - Rebūke Remix", 210, "Green Velvet", "Rebūke")
        val video = track("Green Velvet - Thoughts (Rebūke Remix)", 211, "Green Velvet")
        assertThat(TrackMatchScore.best(source, listOf(video))?.candidate).isEqualTo(video)
    }

    @Test
    fun `remixer title credit cannot hide a conflicting guest performer`() {
        val source = track("Together (Rebūke Remix)", 210, "Primary", "Guest A", "Rebūke")
        val wrong = track("Together (Rebūke Remix)", 210, "Primary", "Guest B")
        assertThat(TrackMatchScore.best(source, listOf(wrong))).isNull()
    }
}
