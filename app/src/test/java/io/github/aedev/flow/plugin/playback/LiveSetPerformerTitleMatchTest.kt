package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.Test

class LiveSetPerformerTitleMatchTest {
    private val original =
        TrackDescriptor(
            EntityRef(EntityKind.TRACK, "soundcloud:tracks:1562058397"),
            "Miss Monique At The Biosphere Museum, In Montreal for Cercle",
            artists = listOf(ArtistCredit("Miss Monique")),
            durationMs = 7_985_652L,
        )
    private val found =
        TrackDescriptor(
            EntityRef(EntityKind.MUSIC_VIDEO, "QPPFM8NyuaQ"),
            "Miss Monique at the Biosphere Museum in Montreal, Canada for Cercle",
            artists = listOf(ArtistCredit("Cercle")),
            durationMs = 7_986_000L,
            hasVideo = true,
            ids = mapOf("yt" to "QPPFM8NyuaQ"),
        )

    @Test
    fun `reported Cercle recording matches performer credited at the start of its title`() {
        assertThat(TrackMatchScore.best(original, listOf(found))?.candidate).isEqualTo(found)
    }

    @Test
    fun `performer prefix does not admit another event year performer or short excerpt`() {
        for (candidate in listOf(
            found.copy(title = found.title.replace("Montreal", "Paris")),
            found.copy(title = found.title + " 2025"),
            found.copy(title = found.title.replace("Miss Monique", "Miss Nine")),
            found.copy(durationMs = 341_000L),
            found.copy(artists = listOf(ArtistCredit("Miss Monique Tribute"))),
        )) {
            assertThat(TrackMatchScore.best(original, listOf(candidate))).isNull()
        }
    }
}
