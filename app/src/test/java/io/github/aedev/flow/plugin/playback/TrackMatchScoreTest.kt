package io.github.aedev.flow.plugin.playback

import com.google.common.truth.Truth.assertThat
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import org.junit.Test

class TrackMatchScoreTest {
    private fun track(
        title: String,
        artist: String,
        seconds: Long?,
        ids: Map<String, String> = emptyMap(),
        id: String = title,
    ) = TrackDescriptor(
        ref = EntityRef(EntityKind.TRACK, id),
        title = title,
        artists = listOf(ArtistCredit(artist)),
        durationMs = seconds?.let { it * 1000 },
        ids = ids,
    )

    @Test
    fun `non Latin titles and artists remain distinct`() {
        val original = track("夜に駆ける", "ヨアソビ", 240)
        val unrelated = track("紅蓮華", "リサ", 240)
        assertThat(TrackMatchScore.normalize(original.title)).isEqualTo(original.title)
        assertThat(TrackMatchScore.score(original, original)).isEqualTo(1.0)
        assertThat(TrackMatchScore.best(original, listOf(unrelated, original))?.candidate).isSameInstanceAs(original)
        assertThat(TrackMatchScore.best(original, listOf(unrelated))).isNull()
    }

    @Test
    fun `missing text and blank ISRC do not establish a match`() {
        val original = track("", "", 240, mapOf("isrc" to ""))
        val candidate = track("", "", 240, mapOf("isrc" to ""))
        assertThat(TrackMatchScore.similarity("", "")).isEqualTo(0.0)
        assertThat(TrackMatchScore.best(original, listOf(candidate))).isNull()
    }

    private val spotify = track("Sky and Sand", "Paul Kalkbrenner", 238, mapOf("spotify" to "4uLU"))

    @Test
    fun `the same song by the same artist at the same length is nearly certain`() {
        assertThat(TrackMatchScore.score(spotify, track("Sky and Sand", "Paul Kalkbrenner", 239))).isAtLeast(0.95)
    }

    @Test
    fun `featuring credits, remaster notes and punctuation do not count against a match`() {
        val candidate = track("Sky & Sand (feat. Fritz Kalkbrenner) [2021 Remaster]", "Paul Kalkbrenner", 238)
        assertThat(TrackMatchScore.score(spotify, candidate)).isAtLeast(0.8)
    }

    @Test
    fun `remastered version is a catalog label for the same recording`() {
        val source = track("Sky and Sand (2021 Remaster)", "Paul Kalkbrenner", 238)
        val candidate = track("Sky & Sand (2021 Remastered Version)", "Paul Kalkbrenner", 238)
        assertThat(TrackMatchScore.best(source, listOf(candidate))?.candidate).isSameInstanceAs(candidate)
    }

    @Test
    fun `another artist's song of the same name scores well below the same artist's`() {
        val same = TrackMatchScore.score(spotify, track("Sky and Sand", "Paul Kalkbrenner", 238))
        val other = TrackMatchScore.score(spotify, track("Sky and Sand", "Kygo", 238))
        assertThat(other).isLessThan(same - 0.2)
    }

    @Test
    fun `an unknown length is neutral, and a closer length never scores lower`() {
        assertThat(TrackMatchScore.durationScore(238_000, null)).isEqualTo(0.5)
        val scores = listOf(0L, 3, 7, 20, 60).map { TrackMatchScore.durationScore(238_000, (238 + it) * 1000) }
        assertThat(scores).isInOrder(Comparator.reverseOrder<Double>())
    }

    @Test
    fun `a shared ISRC is certain whatever the titles say`() {
        val isrc = track("Sky and Sand", "Paul Kalkbrenner", 238, mapOf("isrc" to "DEA620900166"))
        val candidate = track("Sky & Sand - Original", "Paul K", 120, mapOf("isrc" to "dea620900166"))
        assertThat(TrackMatchScore.score(isrc, candidate)).isEqualTo(1.0)
    }

    @Test
    fun `a studio upload is picked over a live one the listener did not ask for`() {
        val live = track("Sky and Sand (Live)", "Paul Kalkbrenner", 238, id = "live")
        val studio = track("Sky and Sand", "Paul Kalkbrenner", 244, id = "studio")
        assertThat(
            TrackMatchScore
                .best(spotify, listOf(live, studio))
                ?.candidate
                ?.ref
                ?.providerId,
        ).isEqualTo("studio")
    }

    @Test
    fun `nothing is played when no candidate is close enough`() {
        assertThat(TrackMatchScore.best(spotify, listOf(track("Blue Monday", "New Order", 450)))).isNull()
        assertThat(TrackMatchScore.best(spotify, emptyList())).isNull()
    }

    @Test
    fun `same artist and similar duration cannot rescue an unrelated title`() {
        val original = track("Moments (feat. Gavin James)", "Bliss n Eso", 237)
        val wrong = track("Home Is Where The Heart Is", "Bliss n Eso", 239)
        assertThat(TrackMatchScore.best(original, listOf(wrong))).isNull()
    }

    @Test
    fun `same title and duration cannot rescue a different primary performer`() {
        val original = track("Bad Blood", "Taylor Swift", 210)
        val wrong = track("Bad Blood", "Bastille", 210)
        assertThat(TrackMatchScore.best(original, listOf(wrong))).isNull()
    }

    @Test
    fun `shared featured artist cannot rescue conflicting primary performers`() {
        val original =
            track("Together", "First Artist", 210)
                .copy(artists = listOf(ArtistCredit("First Artist"), ArtistCredit("Shared Guest")))
        val wrong =
            track("Together", "Second Artist", 210)
                .copy(artists = listOf(ArtistCredit("Second Artist"), ArtistCredit("Shared Guest")))
        assertThat(TrackMatchScore.best(original, listOf(wrong))).isNull()
    }

    @Test
    fun `tribute performer is not the credited original performer`() {
        val original = track("Runaway", "AURORA", 240)
        assertThat(TrackMatchScore.best(original, listOf(track("Runaway", "AURORA Tribute Band", 240)))).isNull()
    }

    @Test
    fun `creative recording variants never substitute for the studio recording`() {
        val original = track("Runaway", "AURORA", 240)
        for (variant in listOf("Live", "Acoustic", "Remix", "Radio Edit", "Instrumental", "Sped Up", "Slowed", "Cover", "Version")) {
            assertThat(TrackMatchScore.best(original, listOf(track("Runaway ($variant)", "AURORA", 240)))).isNull()
            assertThat(TrackMatchScore.best(track("Runaway ($variant)", "AURORA", 240), listOf(original))).isNull()
        }
    }

    @Test
    fun `named remix and live venue identities must agree`() {
        for ((wanted, wrong) in listOf(
            "Runaway (DJ One Remix)" to "Runaway (DJ Two Remix)",
            "Runaway (Live at Oslo)" to "Runaway (Live at London)",
        )) {
            assertThat(TrackMatchScore.best(track(wanted, "AURORA", 240), listOf(track(wrong, "AURORA", 240)))).isNull()
        }
    }

    @Test
    fun `material duration conflicts reject otherwise exact metadata`() {
        assertThat(TrackMatchScore.best(spotify, listOf(track("Sky and Sand", "Paul Kalkbrenner", 290)))).isNull()
        assertThat(TrackMatchScore.best(spotify, listOf(track("Sky and Sand", "Paul Kalkbrenner", 180)))).isNull()
    }

    @Test
    fun `unknown duration still permits strong title and performer agreement`() {
        val candidate = track("Sky and Sand", "Paul Kalkbrenner", null)
        assertThat(TrackMatchScore.best(spotify, listOf(candidate))?.candidate).isSameInstanceAs(candidate)
    }

    @Test
    fun `featuring credits may move between title and artist metadata`() {
        val original = track("Moments (feat. Gavin James)", "Bliss n Eso", 237)
        val candidate =
            track("Moments", "Bliss n Eso", 239)
                .copy(artists = listOf(ArtistCredit("Bliss n Eso"), ArtistCredit("Gavin James")))
        assertThat(TrackMatchScore.best(original, listOf(candidate))?.candidate).isSameInstanceAs(candidate)
        assertThat(
            TrackMatchScore.best(candidate, listOf(track("Moments feat. Gavin James", "Bliss n Eso", 239)))?.candidate,
        ).isNotNull()
    }

    @Test
    fun `contradictory known featured performers reject a different recording`() {
        val original = track("Moments (feat. Gavin James)", "Bliss n Eso", 237)
        val wrongTitleCredit = track("Moments (feat. Another Guest)", "Bliss n Eso", 237)
        val wrongMetadataCredit =
            track("Moments", "Bliss n Eso", 237)
                .copy(artists = listOf(ArtistCredit("Bliss n Eso"), ArtistCredit("Another Guest")))
        assertThat(TrackMatchScore.best(original, listOf(wrongTitleCredit))).isNull()
        assertThat(TrackMatchScore.best(original, listOf(wrongMetadataCredit))).isNull()
    }

    @Test
    fun `a tribute guest cannot stand for the original featured performer`() {
        val original = track("Moments (feat. Gavin James)", "Bliss n Eso", 237)
        val candidate = track("Moments (feat. Gavin James Tribute Band)", "Bliss n Eso", 237)
        assertThat(TrackMatchScore.best(original, listOf(candidate))).isNull()
    }

    @Test
    fun `explicit ISRC identity takes priority over an earlier fuzzy match`() {
        val original = track("Sky and Sand", "Paul Kalkbrenner", 238, mapOf("isrc" to "DEA620900166"))
        val fuzzy = track("Sky and Sand", "Paul Kalkbrenner", 238)
        val identified = track("Another Display Title", "Another Display Credit", 120, mapOf("isrc" to "dea620900166"))
        assertThat(TrackMatchScore.best(original, listOf(fuzzy, identified))?.candidate).isSameInstanceAs(identified)
    }

    @Test
    fun `punctuation in recording qualifiers does not alter named version identity`() {
        val candidate = track("Runaway-DJ One Remix", "AURORA", 240)
        assertThat(
            TrackMatchScore.best(track("Runaway (DJ One Remix)", "AURORA", 240), listOf(candidate))?.candidate,
        ).isSameInstanceAs(candidate)
    }

    @Test
    fun `localized acoustic and live qualifiers retain recording family identity`() {
        for ((wanted, found) in listOf(
            "Runaway (Acoustic Version)" to "Runaway (Akustik)",
            "Runaway (Acoustic)" to "Runaway (Acoustique)",
            "Runaway (Live)" to "Runaway (Ao Vivo)",
        )) {
            val candidate = track(found, "AURORA", 240)
            assertThat(TrackMatchScore.best(track(wanted, "AURORA", 240), listOf(candidate))?.candidate).isSameInstanceAs(candidate)
        }
    }

    @Test
    fun `catalog decorations and standard video labels preserve a strong match`() {
        val candidate = track("Sky & Sand (feat. Fritz Kalkbrenner) [2021 Remaster] (Official Music Video)", "Paul Kalkbrenner", 240)
        assertThat(TrackMatchScore.best(spotify, listOf(candidate))?.candidate).isSameInstanceAs(candidate)
    }

    @Test
    fun `explicit equivalent versions remain playable`() {
        for ((wanted, found) in listOf(
            "Runaway (Acoustic Version)" to "Runaway - Acoustic",
            "Cupid (Twin Ver.)" to "Cupid (Twin Version)",
            "Runaway [DJ One Remix]" to "Runaway - DJ One Remix",
        )) {
            val candidate = track(found, "AURORA", 240)
            assertThat(TrackMatchScore.best(track(wanted, "AURORA", 240), listOf(candidate))?.candidate).isSameInstanceAs(candidate)
        }
    }

    @Test
    fun `Unicode canonical forms and Latin accents preserve performer identity`() {
        val candidate = track("Deja Vu", "Beyonce", 240)
        assertThat(
            TrackMatchScore.best(track("De\u0301ja\u0300 Vu", "Beyoncé", 240), listOf(candidate))?.candidate,
        ).isSameInstanceAs(candidate)
        val fullWidth = track("Ｓｋｙ ａｎｄ Ｓａｎｄ", "Ｐａｕｌ Ｋａｌｋｂｒｅｎｎｅｒ", 238)
        assertThat(TrackMatchScore.best(spotify, listOf(fullWidth))?.candidate).isSameInstanceAs(fullWidth)
    }

    @Test
    fun `punctuation only track titles retain identity`() {
        val original = track("...", "Known Artist", 210)
        val same = track("...", "Known Artist", 210)
        assertThat(TrackMatchScore.best(original, listOf(track("!!!", "Known Artist", 210)))).isNull()
        assertThat(TrackMatchScore.best(original, listOf(same))?.candidate).isSameInstanceAs(same)
    }

    @Test
    fun `hard ISRC evidence remains authoritative through best selection`() {
        val original = track("Sky and Sand", "Paul Kalkbrenner", 238, mapOf("isrc" to "DEA620900166"))
        val candidate = track("Another Song (Live)", "Another Performer", 120, mapOf("isrc" to "dea620900166"))
        assertThat(TrackMatchScore.best(original, listOf(candidate))?.candidate).isSameInstanceAs(candidate)
    }

    @Test
    fun `a track is known by its ISRC when it has one, else by all its ids`() {
        assertThat(PluginTrackMatcher.fingerprint(track("a", "b", 1, mapOf("isrc" to "de1", "spotify" to "x")))).isEqualTo("isrc:DE1")
        assertThat(
            PluginTrackMatcher.fingerprint(track("a", "b", 1, mapOf("spotify" to "x", "beatport" to "9"))),
        ).isEqualTo("beatport:9,spotify:x")
        assertThat(PluginTrackMatcher.fingerprint(track("a", "b", 1, id = "r"))).isEqualTo("ref:TRACK:r")
    }
}
