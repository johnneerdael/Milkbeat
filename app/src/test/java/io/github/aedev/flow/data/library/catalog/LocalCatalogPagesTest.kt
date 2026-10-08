package io.github.aedev.flow.data.library.catalog

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.library.index.LibraryGroupRow
import io.github.aedev.flow.data.library.index.LibraryPlaylistRow
import io.github.aedev.flow.data.library.index.LibraryReleaseRow
import io.github.aedev.flow.data.library.index.LibraryTrackEntity
import io.github.aedev.flow.data.library.index.LibraryTrackRow
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.ItemView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LocalCatalogPagesTest {
    private val text =
        LocalCatalogText(
            recentlyAdded = "Recently added",
            artists = "Artists",
            releases = "Releases",
            playlists = "Playlists",
            labels = "Labels",
            years = "Years",
            tracks = "Tracks",
            variousArtists = "Various Artists",
            unknownYear = "Year unknown",
            moreFrom = { "More from $it" },
            bpm = { "$it BPM" },
            trackCount = { "$it tracks" },
            releaseCount = { "$it releases" },
            yearRange = { a, b -> "$a–$b" },
            month = { "Month $it" },
        )
    private val pages = LocalCatalogPages(text) { row -> "local_${row.track.id}".takeUnless { row.track.folderId == "gone" } }

    @Test fun homeShowsGenreChipsAndOnlyTheShelvesThatHaveSomething() {
        val page =
            pages.home(
                LocalHomeContent(
                    genres = listOf(group("Melodic Techno & House", 30), group("Techno", 10)),
                    genre = null,
                    recent = (1..30).map { release("r$it") },
                    artists = listOf(group("Arma", 3)),
                    releases = listOf(release("r1")),
                    playlists = emptyList(),
                    labels = listOf(group(".defaultbox", 2)),
                    years = listOf(group("2024", 2)),
                ),
            )

        assertThat(page.filters!!.options.map { it.label }).containsExactly("Melodic Techno & House", "Techno").inOrder()
        val shelves = page.blocks.filterIsInstance<CollectionBlock>()
        assertThat(shelves.map { it.header?.title }).containsExactly("Recently added", "Artists", "Releases", "Labels", "Years").inOrder()
        val recent = shelves.first()
        assertThat(recent.items).hasSize(LocalCatalogPages.SHELF_SIZE)
        assertThat(LocalRef.parse(recent.showAll!!)).isEqualTo(LocalRef.All(LocalSection.RECENT, null))
        assertThat(shelves[1].defaultItemView).isEqualTo(ItemView.ARTIST_PORTRAIT)
        assertThat(shelves[1].showAll).isNull()
        assertThat(
            page.blocks
                .flatMap {
                    (it as CollectionBlock).items
                }.map { it.id }
                .toSet(),
        ).hasSize(page.blocks.sumOf { (it as CollectionBlock).items.size })
    }

    @Test fun searchPageShowsSongsReleasesAndArtistsAndLeavesOutEmptyOnes() {
        val page = pages.search("arma", listOf(track("t1")), listOf(release("r1")), emptyList())

        val blocks = page.blocks.filterIsInstance<CollectionBlock>()
        assertThat(blocks.map { it.header?.title }).containsExactly("Tracks", "Releases").inOrder()
        assertThat(blocks.first().layout).isEqualTo(CollectionLayout.TRACK_TABLE)
        assertThat(pages.search("none", emptyList(), emptyList(), emptyList()).blocks).isEmpty()
    }

    @Test fun releasePageCreditsACompilationToVariousArtistsAndNumbersItsTracks() {
        val release = release("bp:1", releaseArtist = "A, B, C, D", label = ".defaultbox", catalogNumber = "DBR015")
        val page =
            pages.release(
                release,
                listOf(track("t2", trackNumber = 2, bpm = 140, key = "3A"), track("t1", trackNumber = 1)),
                moreFromLabel = listOf(release, release("bp:2")),
                moreFromArtist = emptyList(),
            )

        val header = page.blocks.first() as EntityHeader
        assertThat(header.style).isEqualTo(HeaderStyle.COVER)
        assertThat(header.attribution!!.name).isEqualTo("Various Artists")
        assertThat(header.attribution!!.entity).isNull()
        assertThat(header.details).containsExactly(".defaultbox · DBR015", "2024", "2 tracks").inOrder()
        val table = page.blocks[1] as CollectionBlock
        assertThat(table.layout).isEqualTo(CollectionLayout.TRACK_TABLE)
        assertThat(table.items.map { it.ordinal }).containsExactly(2, 1).inOrder()
        assertThat(table.items.first().subtitle).isEqualTo("Arma · 140 BPM · 3A")
        val more = page.blocks[2] as CollectionBlock
        assertThat(more.header!!.title).isEqualTo("More from .defaultbox")
        assertThat(more.items.map { LocalRef.parse(it.entity) }).containsExactly(LocalRef.Release("bp:2"))
    }

    @Test fun soleReleaseArtistLinksToTheirPage() {
        val page = pages.release(release("bp:1", releaseArtist = "Arma"), listOf(track("t1")), emptyList(), listOf(release("bp:9")))
        val header = page.blocks.first() as EntityHeader
        assertThat(LocalRef.parse(header.attribution!!.entity!!)).isEqualTo(LocalRef.Artist("Arma"))
        assertThat((page.blocks.last() as CollectionBlock).header!!.title).isEqualTo("More from Arma")
    }

    @Test fun tracksOfARemovedFolderAreLeftOutAndPlayableOnesCarryADescriptor() {
        val page = pages.playlist(LibraryPlaylistRow("p", "Friday", 2, null), listOf(track("t1"), track("t2", folderId = "gone")))
        val items = (page.blocks[1] as CollectionBlock).items
        assertThat(items).hasSize(1)
        val item = items.single()
        assertThat(item.entity.kind).isEqualTo(EntityKind.TRACK)
        assertThat(item.track!!.ref.providerId).isEqualTo("local_t1")
        assertThat(item.track!!.artists.map { LocalRef.parse(it.entity!!) }).containsExactly(LocalRef.Artist("Arma"))
        assertThat(LocalRef.parse(item.track!!.albumRef!!)).isEqualTo(LocalRef.Release("bp:1"))
    }

    @Test fun showAllListsSplitIntoLetterShelvesAcrossPages() {
        val names = ('A'..'L').map { "$it artist" } + "2 Unlimited"
        val ref = LocalRef.All(LocalSection.ARTISTS, "Techno")
        val first = pages.all(ref, emptyList(), names.map { group(it, 1) }, page = 0)
        assertThat(
            first.blocks.map { (it as CollectionBlock).header!!.title },
        ).containsExactly("A", "B", "C", "D", "E", "F", "G", "H").inOrder()
        assertThat(first.nextCursor).isEqualTo("1")
        val second = pages.all(ref, emptyList(), names.map { group(it, 1) }, page = 1)
        assertThat(second.blocks.map { (it as CollectionBlock).header!!.title }).containsExactly("I", "J", "K", "L", "#").inOrder()
        assertThat(second.nextCursor).isNull()
    }

    private fun group(
        name: String,
        tracks: Int,
    ) = LibraryGroupRow(name, tracks, 1, null, 2020, 2024)

    private fun release(
        key: String,
        releaseArtist: String = "Arma",
        label: String = "",
        catalogNumber: String = "",
    ) = LibraryReleaseRow(key, "Album $key", "First", releaseArtist, "Arma", label, catalogNumber, 2024, 2, 0L, null)

    private fun track(
        id: String,
        trackNumber: Int? = null,
        bpm: Int? = null,
        key: String = "",
        folderId: String = "nas",
    ) = LibraryTrackRow(
        LibraryTrackEntity(
            id = id,
            folderId = folderId,
            location = "$id.mp3",
            path = "$id.mp3",
            sizeBytes = 1,
            modifiedMs = 1,
            title = id,
            artist = "Arma",
            album = "Album",
            releaseKey = "bp:1",
            releaseArtist = "Arma",
            genre = "Techno",
            label = "",
            catalogNumber = "",
            year = 2024,
            trackNumber = trackNumber,
            discNumber = null,
            bpm = bpm,
            musicalKey = key,
            energy = null,
            durationMs = 333_000,
            addedAtMs = 0,
        ),
        artworkPath = null,
    )
}
