package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioMatches
import nl.neerdael.milkbeat.plugin.PluginJson
import org.junit.Test

class DatedFestivalSetMatchTest {
    private val source =
        TrackDescriptor(
            EntityRef(EntityKind.TRACK, "2368284842"),
            "Massano- Live at TML 2026 (Boom, Belgium - 25-07-2026)",
            artists = listOf(ArtistCredit("Massano")),
            durationMs = 3_560_783L,
            ids = mapOf("soundcloud" to "2368284842"),
        )
    private val video =
        TrackDescriptor(
            EntityRef(EntityKind.MUSIC_VIDEO, "eeNljOHahxY"),
            "Massano - Live at Tomorrowland 2026 (Full Set @ Freedom Stage)",
            artists = listOf(ArtistCredit("Massano")),
            durationMs = 3_599_000L,
            hasVideo = true,
            ids = mapOf("yt" to "eeNljOHahxY"),
            description = "Relive Massano’s full audio-visual DJ set recorded live from Tomorrowland Weekend 2 - 25.07.2026.",
        )

    @Test
    fun `reported Massano set matches the date in its video description`() {
        assertThat(TrackMatchScore.best(source, listOf(video))?.candidate).isEqualTo(video)
        val reupload = source.copy(artists = listOf(ArtistCredit("Victor Meneses")))
        assertThat(TrackMatchScore.best(reupload, listOf(video))?.candidate).isEqualTo(video)
    }

    @Test
    fun `festival alias is symmetric and accepts ISO recording dates`() {
        val original = source.copy(title = source.title.replace("TML", "Tomorrowland"))
        val found =
            video.copy(
                title = video.title.replace("Tomorrowland", "TML"),
                description = video.description!!.replace("25.07.2026", "2026-07-25"),
            )
        assertThat(TrackMatchScore.best(original, listOf(found))?.candidate).isEqualTo(found)
    }

    @Test
    fun `another performer day edition or clip is not the same set`() {
        for (candidate in listOf(
            video.copy(title = video.title.replace("Massano", "Cassian")),
            video.copy(description = video.description!!.replace("25.07.2026", "18.07.2026")),
            video.copy(description = video.description!!.replace("25.07.2026", "25.07.2025")),
            video.copy(title = video.title.replace("Tomorrowland", "Tomorrowland Winter")),
            video.copy(title = video.title.replace("2026", "2025")),
            video.copy(title = video.title + " Remix"),
            video.copy(artists = listOf(ArtistCredit("Massano Tribute"))),
            video.copy(durationMs = 300_000L),
            video.copy(durationMs = 4_200_000L),
            video.copy(durationMs = null),
            video.copy(description = null),
            video.copy(description = "Published on 25.07.2026"),
            video.copy(description = "Tomorrowland live set uploaded on 25.07.2026"),
            video.copy(description = "Buy tickets for Tomorrowland on 25.07.2026"),
            video.copy(description = video.description!!.replace("25.07.2026", "32.07.2026")),
            video.copy(title = video.title + " 18-07-2026"),
            video.copy(ids = emptyMap()),
            video.copy(hasVideo = false),
        )) {
            assertThat(TrackMatchScore.best(source, listOf(candidate))).isNull()
        }
    }

    @Test
    fun `unrelated years in the tracklist do not change the recording date`() {
        val found = video.copy(description = video.description + "\n44:20 Massano - The Feeling (2025 Private Edit)")
        assertThat(TrackMatchScore.best(source, listOf(found))?.candidate).isEqualTo(found)
    }

    @Test
    fun `live YouTube candidate response selects the reported recording`() {
        val json = javaClass.getResource("/plugin/massano-live-match.json")!!.readText()
        val candidates = PluginJson.decodeFromString(AudioMatches.serializer(), json).candidates
        assertThat(
            TrackMatchScore
                .best(source, candidates)
                ?.candidate
                ?.ref
                ?.providerId,
        ).isEqualTo("eeNljOHahxY")
    }

    @Test
    fun `hyphenated performers retain their complete names`() {
        val set = source.copy(title = source.title.replace("Massano", "DJ-A"), artists = listOf(ArtistCredit("DJ-A")))
        val same = video.copy(title = video.title.replace("Massano", "DJ-A"), artists = listOf(ArtistCredit("DJ-A")))
        val other = same.copy(title = same.title.replace("DJ-A", "DJ-B"), artists = listOf(ArtistCredit("DJ-B")))
        assertThat(TrackMatchScore.best(set, listOf(other))).isNull()
        assertThat(TrackMatchScore.best(set, listOf(same))?.candidate).isEqualTo(same)
    }

    @Test
    fun `festival identity retains the ordinary recording variant checks`() {
        for (variant in listOf("Acoustic", "Edit", "Dub", "VIP", "Instrumental", "Piano", "Demo", "Mono", "Sped up")) {
            assertThat(TrackMatchScore.best(source, listOf(video.copy(title = video.title + " " + variant)))).isNull()
        }
    }

    @Test
    fun `embedded performer punctuation never becomes a title separator`() {
        for (symbol in listOf("@", "|", "•", ":")) {
            val set = source.copy(title = source.title.replace("Massano", "DJ${symbol}A"), artists = listOf(ArtistCredit("DJ${symbol}A")))
            val same = video.copy(title = video.title.replace("Massano", "DJ${symbol}A"), artists = listOf(ArtistCredit("DJ${symbol}A")))
            val other =
                same.copy(
                    title = same.title.replace("DJ${symbol}A", "DJ${symbol}B"),
                    artists = listOf(ArtistCredit("DJ${symbol}B")),
                )
            assertThat(TrackMatchScore.best(set, listOf(other))).isNull()
            assertThat(TrackMatchScore.best(set, listOf(same))?.candidate).isEqualTo(same)
        }
    }

    @Test
    fun `Latin accents do not change the festival performer identity`() {
        val set = source.copy(title = source.title.replace("Massano", "Beyoncé"), artists = listOf(ArtistCredit("Beyoncé")))
        val found = video.copy(title = video.title.replace("Massano", "Beyonce"), artists = listOf(ArtistCredit("Beyonce")))
        assertThat(TrackMatchScore.best(set, listOf(found))?.candidate).isEqualTo(found)
    }
}
