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

    private val bodzin =
        TrackDescriptor(
            EntityRef(EntityKind.TRACK, "soundcloud:tracks:2"),
            "STEPHAN BODZIN - VSNZ Winter Festival 2025",
            artists = listOf(ArtistCredit("Stephan Bodzin")),
            durationMs = 6_468_000L,
        )
    private val promoterUpload =
        TrackDescriptor(
            EntityRef(EntityKind.MUSIC_VIDEO, "bpEVpBr5lK4"),
            "VSNZ pres. STEPHAN BODZIN - VSNZ Winter Festival 2025",
            artists = listOf(ArtistCredit("VSNZ")),
            durationMs = 7_085_000L,
            hasVideo = true,
            ids = mapOf("yt" to "bpEVpBr5lK4"),
        )

    @Test
    fun `a promoter's longer upload of the same set, event and year matches`() {
        assertThat(TrackMatchScore.best(bodzin, listOf(promoterUpload))?.candidate).isEqualTo(promoterUpload)
    }

    @Test
    fun `the set length allowance needs a long set, 15 percent and the same event year`() {
        for (candidate in listOf(
            promoterUpload.copy(durationMs = 7_500_000L),
            promoterUpload.copy(title = promoterUpload.title.replace("2025", "2024")),
            promoterUpload.copy(title = promoterUpload.title.replace(" 2025", "")),
            promoterUpload.copy(title = promoterUpload.title.replace("Winter", "Summer")),
            promoterUpload.copy(title = "VSNZ pres. ANNA - VSNZ Winter Festival 2025"),
        )) {
            assertThat(TrackMatchScore.best(bodzin, listOf(candidate))).isNull()
        }
        val undated = bodzin.copy(title = "STEPHAN BODZIN - VSNZ Winter Festival")
        assertThat(
            TrackMatchScore.best(undated, listOf(promoterUpload.copy(title = "VSNZ pres. STEPHAN BODZIN - VSNZ Winter Festival"))),
        ).isNull()
    }

    @Test
    fun `a promoter credit introduces exactly the performer, not an imitation`() {
        for (title in listOf(
            "VSNZ pres. STEPHAN BODZIN Tribute - VSNZ Winter Festival 2025",
            "VSNZ pres. STEPHAN BODZIN and Friends - VSNZ Winter Festival 2025",
        )) {
            for (length in listOf(6_468_000L, 7_085_000L)) {
                assertThat(TrackMatchScore.best(bodzin, listOf(promoterUpload.copy(title = title, durationMs = length)))).isNull()
            }
        }
        val presents = promoterUpload.copy(title = "VSNZ presents Stephan Bodzin - VSNZ Winter Festival 2025")
        assertThat(TrackMatchScore.best(bodzin, listOf(presents))?.candidate).isEqualTo(presents)
    }

    @Test
    fun `long recordings that do not name a live set keep the ten second length rule`() {
        val suite = bodzin.copy(title = "Stephan Bodzin - The 2025 Suite", durationMs = 1_500_000L)
        val upload = promoterUpload.copy(title = "Stephan Bodzin - The 2025 Suite", durationMs = 1_620_000L)
        assertThat(TrackMatchScore.best(suite, listOf(upload))).isNull()
        assertThat(TrackMatchScore.best(suite, listOf(upload.copy(durationMs = 1_505_000L)))?.candidate).isNotNull()
    }

    @Test
    fun `tracks shorter than a set keep the ten second length rule`() {
        val song = bodzin.copy(title = "Stephan Bodzin - Powers of Ten 2025", durationMs = 600_000L)
        val video = promoterUpload.copy(title = "Stephan Bodzin - Powers of Ten 2025", durationMs = 660_000L)
        assertThat(TrackMatchScore.best(song, listOf(video))).isNull()
        assertThat(TrackMatchScore.best(song, listOf(video.copy(durationMs = 605_000L)))?.candidate).isNotNull()
    }
}
