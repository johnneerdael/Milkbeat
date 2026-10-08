package io.github.aedev.flow.data.library.index

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LibraryTextSearchTest {
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        dao = database.dao()
        runBlocking<Unit> {
            dao.saveTracks(
                listOf(
                    track("1", title = "Strobe", artist = "deadmau5", album = "For Lack of a Better Name"),
                    track("2", title = "100% Pure", artist = "Arma", album = "Origins"),
                    track("3", title = "Under_score", artist = "Arma", album = "Origins"),
                ),
                listOf(
                    LibraryTrackArtistEntity("1", 0, "deadmau5"),
                    LibraryTrackArtistEntity("2", 0, "Arma"),
                    LibraryTrackArtistEntity("3", 0, "Arma"),
                ),
            )
        }
    }

    @After fun tearDown() = database.close()

    @Test fun tracksMatchTitleOrArtistIgnoringCase() =
        runBlocking<Unit> {
            val byTitle = dao.tracks(LibraryQueries.tracks(LibraryFilter(text = "STROBE"), TrackOrder.RELEASE))
            val byArtist = dao.tracks(LibraryQueries.tracks(LibraryFilter(text = "arm"), TrackOrder.RELEASE))

            assertThat(byTitle.map { it.track.id }).containsExactly("1")
            assertThat(byArtist.map { it.track.id }).containsExactly("2", "3")
        }

    @Test fun wildcardsInTheQueryMatchLiterally() =
        runBlocking<Unit> {
            assertThat(dao.tracks(LibraryQueries.tracks(LibraryFilter(text = "%"), TrackOrder.RELEASE)).map { it.track.id })
                .containsExactly("2")
            assertThat(dao.tracks(LibraryQueries.tracks(LibraryFilter(text = "_"), TrackOrder.RELEASE)).map { it.track.id })
                .containsExactly("3")
        }

    @Test fun releasesMatchAlbumAndArtistsMatchName() =
        runBlocking<Unit> {
            val releases = dao.releases(LibraryQueries.releases(LibraryFilter(text = "origin"), ReleaseOrder.NEWEST))
            val artists = dao.groups(LibraryQueries.groups(GroupKind.ARTIST, LibraryFilter(text = "mau"), GroupOrder.NAME))

            assertThat(releases.map { it.album }).containsExactly("Origins")
            assertThat(artists.map { it.name }).containsExactly("deadmau5")
        }

    private fun track(
        id: String,
        title: String,
        artist: String,
        album: String,
    ) = LibraryTrackEntity(
        id = id,
        folderId = "nas",
        location = "$id.mp3",
        path = "$id.mp3",
        sizeBytes = 1,
        modifiedMs = 1,
        title = title,
        artist = artist,
        album = album,
        releaseKey = "release:$album",
        releaseArtist = artist,
        genre = "",
        label = "",
        catalogNumber = "",
        year = 2020,
        trackNumber = 1,
        discNumber = 1,
        bpm = null,
        musicalKey = "",
        energy = null,
        durationMs = 1,
        addedAtMs = 1,
    )
}
