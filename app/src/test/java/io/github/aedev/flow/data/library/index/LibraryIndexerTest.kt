package io.github.aedev.flow.data.library.index

import android.content.Context
import androidx.media3.common.ParserException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.MusicFolderEntry
import io.github.aedev.flow.data.folders.MusicFolderKind
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LibraryIndexerTest {
    private lateinit var context: Context
    private lateinit var database: LibraryDatabase
    private lateinit var dao: LibraryDao
    private val sources = FakeSources()
    private lateinit var indexer: LibraryIndexer

    private val nas = MusicFolder(id = "nas", revision = "r1", name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "music")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, LibraryDatabase::class.java).allowMainThreadQueries().build()
        dao = database.dao()
        indexer = LibraryIndexer(sources, dao, LibraryArtworkStore(context))
        sources.folders = listOf(nas)
    }

    @After fun tearDown() {
        database.close()
        File(context.filesDir, "library-artwork").deleteRecursively()
    }

    @Test fun firstScanIndexesEveryFileWithItsCreditsAndOneCoverPerRelease() =
        runBlocking {
            sources.file(
                "Techno/one.mp3",
                tags(title = "One", artists = listOf("Arma", "Tag & Wandrach"), releaseId = "1"),
                art = byteArrayOf(1),
            )
            sources.file("Techno/two.mp3", tags(title = "Two", artists = listOf("Arma"), releaseId = "1"), art = byteArrayOf(1))
            sources.file("loose.mp3", LibraryTags())

            assertThat(indexer.scan().changed).isTrue()

            assertThat(dao.trackCount()).isEqualTo(3)
            val tracks = dao.tracks(LibraryQueries.tracks(LibraryFilter(artist = "arma"), TrackOrder.RELEASE))
            assertThat(tracks.map { it.track.title }).containsExactly("One", "Two").inOrder()
            assertThat(tracks.first().track.artist).isEqualTo("Arma, Tag & Wandrach")
            assertThat(tracks.map { it.artworkPath }.distinct()).hasSize(1)
            assertThat(File(tracks.first().artworkPath!!).exists()).isTrue()
            val loose = dao.tracks(LibraryQueries.tracks(LibraryFilter(releaseKey = "track:nas|loose.mp3"), TrackOrder.RELEASE))
            assertThat(loose.single().track.title).isEqualTo("loose")
            assertThat(dao.meta(LibraryIndexer.META_REVISION)).isNotNull()
        }

    @Test fun unchangedFilesAreNotReadAgainAndChangesAreReconciled() =
        runBlocking {
            sources.file("a.mp3", tags(title = "A"))
            sources.file("b.mp3", tags(title = "B"))
            indexer.scan()
            val revision = dao.meta(LibraryIndexer.META_REVISION)
            sources.reads.clear()

            assertThat(indexer.scan().changed).isFalse()
            assertThat(sources.reads).isEmpty()
            assertThat(dao.meta(LibraryIndexer.META_REVISION)).isEqualTo(revision)

            sources.file("a.mp3", tags(title = "A2"), modified = 2)
            sources.remove("b.mp3")
            assertThat(indexer.scan().changed).isTrue()
            assertThat(sources.reads).containsExactly("a.mp3")
            assertThat(dao.tracks(LibraryQueries.tracks(LibraryFilter(), TrackOrder.RELEASE)).map { it.track.title }).containsExactly("A2")
            assertThat(dao.meta(LibraryIndexer.META_REVISION)).isNotEqualTo(revision)
        }

    @Test fun unreachableFolderKeepsItsTracksAndRemovedFolderDropsThem() =
        runBlocking {
            sources.file("a.mp3", tags(title = "A"))
            indexer.scan()

            sources.unreachable = true
            assertThat(indexer.scan().complete).isFalse()
            assertThat(dao.trackCount()).isEqualTo(1)
            assertThat(dao.meta(LibraryIndexer.META_SCANNED_FOLDERS)).isEqualTo(LibraryIndexer.foldersFingerprint(listOf(nas)))

            sources.unreachable = false
            sources.folders = emptyList()
            indexer.scan()
            assertThat(dao.trackCount()).isEqualTo(0)
        }

    @Test fun unparsableTagsStillIndexTheFileByName() =
        runBlocking {
            sources.file("Broken Song.mp3", tags(title = "ignored"))
            sources.unparsable += "Broken Song.mp3"
            assertThat(indexer.scan().complete).isTrue()
            val tracks = dao.tracks(LibraryQueries.tracks(LibraryFilter(), TrackOrder.RELEASE))
            assertThat(tracks.single().track.title).isEqualTo("Broken Song")
        }

    @Test fun aFileTheShareDroppedIsReadAgainOnTheNextScan() =
        runBlocking {
            sources.file("a.mp3", tags(title = "A"))
            sources.file("b.mp3", tags(title = "B"))
            sources.dropped += "b.mp3"
            assertThat(indexer.scan().complete).isTrue()
            assertThat(dao.trackCount()).isEqualTo(1)

            sources.dropped.clear()
            sources.reads.clear()
            assertThat(indexer.scan().complete).isTrue()
            assertThat(sources.reads).containsExactly("b.mp3")
            assertThat(dao.trackCount()).isEqualTo(2)
        }

    @Test fun anArtistOnlyCoCreditedIsStillTheOneTheirGroupNames() =
        runBlocking {
            sources.file("z.mp3", tags(title = "Collab", artists = listOf("Zed", "Alpha")))
            indexer.scan()
            val query = LibraryQueries.groups(GroupKind.ARTIST, LibraryFilter(artist = "Zed"), GroupOrder.TRACKS, limit = 1, named = "Zed")
            assertThat(dao.groups(query).single().name).isEqualTo("Zed")
        }

    @Test fun releasesSortByTheirNewestYearWithUndatedOnesLast() =
        runBlocking {
            sources.file("a1.mp3", tags(title = "A1", releaseId = "a", year = null))
            sources.file("a2.mp3", tags(title = "A2", releaseId = "a", year = 2024))
            sources.file("b.mp3", tags(title = "B", releaseId = "b", year = 2020))
            sources.file("c.mp3", tags(title = "C", releaseId = "c", year = null))
            indexer.scan()
            val order = dao.releases(LibraryQueries.releases(LibraryFilter(), ReleaseOrder.NEWEST)).map { it.releaseKey }
            assertThat(order).containsExactly("bp:a", "bp:b", "bp:c").inOrder()
        }

    @Test fun aCoverRemovedFromEveryFileOfItsReleaseIsDropped() =
        runBlocking {
            sources.file("a.mp3", tags(title = "A", releaseId = "1"), art = byteArrayOf(1))
            sources.file("b.mp3", tags(title = "B", releaseId = "1"), art = byteArrayOf(1))
            indexer.scan()
            val cover = File(dao.artwork("bp:1")!!.path)

            sources.file("a.mp3", tags(title = "A", releaseId = "1"), modified = 2)
            indexer.scan()
            assertThat(dao.artwork("bp:1")).isNotNull()

            sources.file("b.mp3", tags(title = "B", releaseId = "1"), modified = 2)
            sources.file("a.mp3", tags(title = "A", releaseId = "1"), modified = 3)
            indexer.scan()
            assertThat(dao.artwork("bp:1")).isNull()
            assertThat(cover.exists()).isFalse()
        }

    @Test fun coversOfReleasesNoLongerIndexedAreDeleted() =
        runBlocking {
            sources.file("a.mp3", tags(title = "A", releaseId = "1"), art = byteArrayOf(1))
            indexer.scan()
            val cover = File(dao.artwork("bp:1")!!.path)
            assertThat(cover.exists()).isTrue()

            sources.remove("a.mp3")
            indexer.scan()
            assertThat(dao.artwork("bp:1")).isNull()
            assertThat(cover.exists()).isFalse()
        }

    @Test fun playlistsResolveTheirEntriesAndFollowTrackChanges() =
        runBlocking {
            sources.file("Techno/one.mp3", tags(title = "One"))
            sources.file("Techno/two.mp3", tags(title = "Two"))
            sources.playlist("Sets/friday.m3u", "#EXTM3U\n../Techno/two.mp3\nD:\\Music\\Techno\\one.mp3\nmissing.mp3\n")
            indexer.scan()

            val playlist = dao.playlistRows().single()
            assertThat(playlist.name).isEqualTo("friday")
            assertThat(playlist.trackCount).isEqualTo(2)
            val ordered = dao.tracks(LibraryQueries.tracks(LibraryFilter(playlistId = playlist.id), TrackOrder.PLAYLIST))
            assertThat(ordered.map { it.track.title }).containsExactly("Two", "One").inOrder()

            sources.remove("Techno/two.mp3")
            sources.file("Techno/two.mp3", tags(title = "Two again"), modified = 5)
            indexer.scan()
            assertThat(dao.playlistRows().single().trackCount).isEqualTo(2)

            sources.remove("Sets/friday.m3u")
            indexer.scan()
            assertThat(dao.playlistRows()).isEmpty()
        }

    @Test fun releaseGroupsCountTracksAndUseTheNewestCover() =
        runBlocking {
            sources.file(
                "old.mp3",
                tags(title = "Old", artists = listOf("Arma"), label = "Afterlife", year = 2019, releaseId = "1"),
                art = byteArrayOf(1),
            )
            sources.file(
                "new.mp3",
                tags(title = "New", artists = listOf("Arma"), label = "Afterlife", year = 2024, releaseId = "2"),
                art = byteArrayOf(2),
            )
            indexer.scan()

            val label = dao.groups(LibraryQueries.groups(GroupKind.LABEL, LibraryFilter(), GroupOrder.TRACKS)).single()
            assertThat(label.trackCount).isEqualTo(2)
            assertThat(label.releaseCount).isEqualTo(2)
            assertThat(label.firstYear).isEqualTo(2019)
            assertThat(label.lastYear).isEqualTo(2024)
            val newest = dao.releases(LibraryQueries.releases(LibraryFilter(), ReleaseOrder.NEWEST)).first()
            assertThat(newest.firstTitle).isEqualTo("New")
            assertThat(label.artworkPath).isEqualTo(newest.artworkPath)
        }

    private fun tags(
        title: String = "",
        artists: List<String> = emptyList(),
        releaseId: String = "",
        label: String = "",
        year: Int? = null,
    ) = LibraryTags(title = title, artists = artists, releaseId = releaseId, label = label, year = year, genre = "Techno")

    private class FakeSources : LibrarySources {
        var folders: List<MusicFolder> = emptyList()
        var unreachable = false
        val reads = mutableListOf<String>()
        val unparsable = mutableSetOf<String>()
        val dropped = mutableSetOf<String>()
        private val files = linkedMapOf<String, Triple<Long, LibraryTags, ByteArray?>>()
        private val playlists = linkedMapOf<String, String>()

        fun file(
            path: String,
            tags: LibraryTags,
            modified: Long = 1,
            art: ByteArray? = null,
        ) {
            files[path] = Triple(modified, tags, art)
        }

        fun playlist(
            path: String,
            text: String,
        ) {
            playlists[path] = text
        }

        fun remove(path: String) {
            files.remove(path)
            playlists.remove(path)
        }

        override suspend fun folders(): List<MusicFolder> = folders

        override suspend fun list(
            folder: MusicFolder,
            location: String,
        ): List<MusicFolderEntry> {
            if (unreachable) throw IOException("asleep")
            val prefix = if (location.isEmpty()) "" else "$location/"
            val children = (files.keys + playlists.keys).filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
            return children.map { it.substringBefore('/') }.distinct().map { name ->
                val path = prefix + name
                val directory = path !in files && path !in playlists
                MusicFolderEntry(name, path, directory, size = 10, modified = files[path]?.first ?: 1)
            }
        }

        override suspend fun tags(
            folder: MusicFolder,
            location: String,
        ): LibraryFileTags {
            reads += location
            if (location in unparsable) throw ParserException.createForMalformedContainer("bad tag", null)
            if (location in dropped) throw IOException("connection reset")
            val (_, tags, art) = files.getValue(location)
            return LibraryFileTags(tags, durationMs = 300_000, artwork = art, artworkMimeType = "image/jpeg")
        }

        override suspend fun text(
            folder: MusicFolder,
            location: String,
        ): String = playlists.getValue(location)
    }
}
