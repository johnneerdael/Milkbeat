package io.github.aedev.flow.data.library.index

import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class LibraryTagsTest {
    private fun text(
        id: String,
        vararg values: String,
    ) = TextInformationFrame(id, null, values.toList())

    private fun txxx(
        description: String,
        value: String,
    ) = TextInformationFrame("TXXX", description, listOf(value))

    @Test fun beatportFileTagsMapToTheLibraryFields() {
        val tags =
            libraryTags(
                listOf(
                    text("TIT2", "Polarity (Original Mix)"),
                    text("TPE1", "10.000 BC"),
                    text("TALB", "Conurbation"),
                    text("TPE2", "DisX3 & Mario Berger, Slight Function, Non Reversible, Arma, Tag & Wandrach"),
                    text("TCON", "Melodic Techno & House"),
                    text("TPUB", ".defaultbox"),
                    txxx("LABEL", ".defaultbox"),
                    txxx("CATALOGNUMBER", "DBR015"),
                    text("TDRC", "2024"),
                    text("TRCK", "8/10"),
                    text("TBPM", "140"),
                    text("TKEY", "3A"),
                    txxx("ENERGYLEVEL", "6"),
                    txxx("BEATPORT_RELEASE_ID", "4438992"),
                    txxx("1T_TAGGEDDATE", "2024-06-08 21:11:32_AT"),
                    text("TLEN", "333000"),
                ),
                ZoneOffset.UTC,
            )

        assertThat(tags.title).isEqualTo("Polarity (Original Mix)")
        assertThat(tags.artists).containsExactly("10.000 BC")
        assertThat(tags.album).isEqualTo("Conurbation")
        assertThat(tags.albumArtists)
            .containsExactly("DisX3 & Mario Berger", "Slight Function", "Non Reversible", "Arma", "Tag & Wandrach")
            .inOrder()
        assertThat(tags.genre).isEqualTo("Melodic Techno & House")
        assertThat(tags.label).isEqualTo(".defaultbox")
        assertThat(tags.catalogNumber).isEqualTo("DBR015")
        assertThat(tags.year).isEqualTo(2024)
        assertThat(tags.trackNumber).isEqualTo(8)
        assertThat(tags.bpm).isEqualTo(140)
        assertThat(tags.musicalKey).isEqualTo("3A")
        assertThat(tags.energy).isEqualTo(6)
        assertThat(tags.releaseId).isEqualTo("4438992")
        assertThat(tags.taggedAtMs).isEqualTo(LocalDateTime.of(2024, 6, 8, 21, 11, 32).toInstant(ZoneOffset.UTC).toEpochMilli())
        assertThat(tags.lengthMs).isEqualTo(333_000L)
    }

    @Test fun storedValuesAndCommasSplitArtistsButAmpersandsDoNot() {
        assertThat(credits(listOf("Arma, Tag & Wandrach"))).containsExactly("Arma", "Tag & Wandrach").inOrder()
        assertThat(credits(listOf("Massano", "Agents Of Time"))).containsExactly("Massano", "Agents Of Time").inOrder()
        assertThat(credits(listOf("Massano\\\\Agents Of Time"))).containsExactly("Massano", "Agents Of Time").inOrder()
        assertThat(credits(listOf("ARTBAT, Artbat", " "))).containsExactly("ARTBAT")
    }

    @Test fun labelFallsBackToPublisherAndYearReadsFullDates() {
        val tags = libraryTags(listOf(text("TPUB", "Afterlife"), text("TDRC", "2023-11-17"), text("TBPM", "122.50")))
        assertThat(tags.label).isEqualTo("Afterlife")
        assertThat(tags.year).isEqualTo(2023)
        assertThat(tags.bpm).isEqualTo(123)
    }

    @Test fun id3v22FramesAndVorbisCommentsReadTheSameFields() {
        val v22 = libraryTags(listOf(text("TT2", "Title"), text("TP1", "Artist"), text("TCO", "Techno")))
        assertThat(v22.title).isEqualTo("Title")
        assertThat(v22.artists).containsExactly("Artist")
        assertThat(v22.genre).isEqualTo("Techno")

        val flac =
            libraryTags(
                listOf(
                    VorbisComment("TITLE", "Song"),
                    VorbisComment("ARTIST", "One"),
                    VorbisComment("ARTIST", "Two"),
                    VorbisComment("ALBUM ARTIST", "One"),
                    VorbisComment("LABEL", "Drumcode"),
                    VorbisComment("DATE", "2019"),
                    VorbisComment("TRACKNUMBER", "3"),
                    VorbisComment("INITIALKEY", "8A"),
                ),
            )
        assertThat(flac.artists).containsExactly("One", "Two").inOrder()
        assertThat(flac.albumArtists).containsExactly("One")
        assertThat(flac.label).isEqualTo("Drumcode")
        assertThat(flac.year).isEqualTo(2019)
        assertThat(flac.trackNumber).isEqualTo(3)
        assertThat(flac.musicalKey).isEqualTo("8A")
    }

    @Test fun untaggedFileReadsAsEmpty() {
        val tags = libraryTags(emptyList())
        assertThat(tags).isEqualTo(LibraryTags())
    }

    @Test fun releaseKeyPrefersBeatportReleaseThenAlbumThenTheTrackItself() {
        assertThat(releaseKey(LibraryTags(releaseId = "4438992", album = "Conurbation"), "t")).isEqualTo("bp:4438992")
        assertThat(releaseKey(LibraryTags(album = "Remixes", albumArtists = listOf("A"), label = "X"), "t"))
            .isNotEqualTo(releaseKey(LibraryTags(album = "Remixes", albumArtists = listOf("B"), label = "X"), "t"))
        assertThat(releaseKey(LibraryTags(album = "Remixes", label = "X"), "t1"))
            .isEqualTo(releaseKey(LibraryTags(album = "REMIXES", label = "x"), "t2"))
        assertThat(releaseKey(LibraryTags(), "folder|a.mp3")).isEqualTo("track:folder|a.mp3")
    }
}
