package io.github.aedev.flow.data.library.catalog

import android.net.Uri
import io.github.aedev.flow.data.library.index.LibraryGroupRow
import io.github.aedev.flow.data.library.index.LibraryIndexer
import io.github.aedev.flow.data.library.index.LibraryPlaylistRow
import io.github.aedev.flow.data.library.index.LibraryReleaseRow
import io.github.aedev.flow.data.library.index.LibraryTrackRow
import nl.neerdael.milkbeat.catalog.ArtistCredit
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.Attribution
import nl.neerdael.milkbeat.catalog.CollectionBlock
import nl.neerdael.milkbeat.catalog.CollectionHeader
import nl.neerdael.milkbeat.catalog.CollectionLayout
import nl.neerdael.milkbeat.catalog.EntityHeader
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.FilterControl
import nl.neerdael.milkbeat.catalog.FilterOption
import nl.neerdael.milkbeat.catalog.HeaderStyle
import nl.neerdael.milkbeat.catalog.ItemView
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import java.io.File

/** The words the local pages are written in, resolved from resources by the provider. */
internal class LocalCatalogText(
    val recentlyAdded: String,
    val artists: String,
    val releases: String,
    val playlists: String,
    val labels: String,
    val years: String,
    val tracks: String,
    val variousArtists: String,
    val unknownYear: String,
    val moreFrom: (String) -> String,
    val bpm: (Int) -> String,
    val trackCount: (Int) -> String,
    val releaseCount: (Int) -> String,
    val yearRange: (Int, Int) -> String,
    val month: (Long) -> String,
)

/** What the home's shelves hold, already narrowed to the selected genre. Shelf lists carry one extra row when there are more. */
internal class LocalHomeContent(
    val genres: List<LibraryGroupRow>,
    val genre: String?,
    val recent: List<LibraryReleaseRow>,
    val artists: List<LibraryGroupRow>,
    val releases: List<LibraryReleaseRow>,
    val playlists: List<LibraryPlaylistRow>,
    val labels: List<LibraryGroupRow>,
    val years: List<LibraryGroupRow>,
)

/**
 * Builds the pages of the local library from index rows. [playableId] gives a track's player id, or
 * null when its folder is gone, and such tracks are left out.
 */
internal class LocalCatalogPages(
    private val text: LocalCatalogText,
    private val playableId: (LibraryTrackRow) -> String?,
) {
    fun home(content: LocalHomeContent): MetadataPage =
        MetadataPage(
            id = "local:home:${content.genre.orEmpty()}",
            blocks =
                listOfNotNull(
                    shelf("recent", text.recentlyAdded, content.recent, LocalRef.All(LocalSection.RECENT, content.genre), ::releaseItem),
                    shelf(
                        "artists",
                        text.artists,
                        content.artists,
                        LocalRef.All(LocalSection.ARTISTS, content.genre),
                        ::artistItem,
                        ItemView.ARTIST_PORTRAIT,
                    ),
                    shelf("releases", text.releases, content.releases, LocalRef.All(LocalSection.RELEASES, content.genre), ::releaseItem),
                    shelf("playlists", text.playlists, content.playlists, null, ::playlistItem, limit = Int.MAX_VALUE),
                    shelf("labels", text.labels, content.labels, LocalRef.All(LocalSection.LABELS, content.genre), ::labelItem),
                    shelf("years", text.years, content.years, null, ::yearItem, limit = Int.MAX_VALUE),
                ),
            filters =
                content.genres.takeIf { it.isNotEmpty() }?.let { genres ->
                    FilterControl(genres.map { FilterOption(it.name, it.name) })
                },
        )

    fun search(
        query: String,
        tracks: List<LibraryTrackRow>,
        releases: List<LibraryReleaseRow>,
        artists: List<LibraryGroupRow>,
    ): MetadataPage =
        MetadataPage(
            id = "local:search:$query",
            blocks =
                listOfNotNull(
                    trackTable("tracks", text.tracks, tracks),
                    shelf("releases", text.releases, releases, null, ::releaseItem),
                    shelf("artists", text.artists, artists, null, ::artistItem, ItemView.ARTIST_PORTRAIT),
                ),
        )

    fun release(
        release: LibraryReleaseRow,
        tracks: List<LibraryTrackRow>,
        moreFromLabel: List<LibraryReleaseRow>,
        moreFromArtist: List<LibraryReleaseRow>,
    ): MetadataPage {
        val ref = LocalRef.Release(release.releaseKey)
        val artists = credits(release.releaseArtist)
        val soleArtist = artists.singleOrNull()
        val header =
            EntityHeader(
                id = "header",
                style = HeaderStyle.COVER,
                entity = ref.entity,
                title = release.title,
                artwork = artwork(release.artworkPath),
                details =
                    listOfNotNull(
                        listOf(release.label, release.catalogNumber).filter(String::isNotBlank).joinToString(" · ").ifEmpty { null },
                        release.year?.toString(),
                        text.trackCount(tracks.size),
                    ),
                attribution = Attribution(name = artistLine(artists), entity = soleArtist?.let { LocalRef.Artist(it).entity }),
                tracks = ref.entity,
            )
        return MetadataPage(
            id = ref.entity.providerId,
            blocks =
                listOfNotNull(
                    header,
                    trackTable("tracks", null, tracks, ordinals = true),
                    release.label.takeIf { it.isNotBlank() }?.let { label ->
                        shelf("label", text.moreFrom(label), moreFromLabel.without(release), LocalRef.Label(label), ::releaseItem)
                    },
                    soleArtist?.let { artist ->
                        shelf("artist", text.moreFrom(artist), moreFromArtist.without(release), LocalRef.Artist(artist), ::releaseItem)
                    },
                ),
        )
    }

    fun artist(
        artist: LibraryGroupRow,
        tracks: List<LibraryTrackRow>,
        releases: List<LibraryReleaseRow>,
        labels: List<LibraryGroupRow>,
    ): MetadataPage {
        val ref = LocalRef.Artist(artist.name)
        return MetadataPage(
            id = ref.entity.providerId,
            blocks =
                listOfNotNull(
                    EntityHeader(
                        id = "header",
                        style = HeaderStyle.PORTRAIT,
                        entity = ref.entity,
                        title = artist.name,
                        artwork = artwork(artist.artworkPath),
                        details = summary(artist),
                        tracks = ref.entity,
                    ),
                    trackTable("tracks", text.tracks, tracks),
                    shelf("releases", text.releases, releases, null, ::releaseItem),
                    shelf("labels", text.labels, labels, null, ::labelItem),
                ),
        )
    }

    fun label(
        label: LibraryGroupRow,
        tracks: List<LibraryTrackRow>,
        releases: List<LibraryReleaseRow>,
        artists: List<LibraryGroupRow>,
    ): MetadataPage = collectionPage(LocalRef.Label(label.name), label, tracks, releases, artists)

    fun year(
        year: LibraryGroupRow,
        tracks: List<LibraryTrackRow>,
        releases: List<LibraryReleaseRow>,
        labels: List<LibraryGroupRow>,
    ): MetadataPage = collectionPage(LocalRef.Year(year.name.toInt()), year, tracks, releases, artists = emptyList(), labels = labels)

    fun playlist(
        playlist: LibraryPlaylistRow,
        tracks: List<LibraryTrackRow>,
    ): MetadataPage {
        val ref = LocalRef.Playlist(playlist.id)
        return MetadataPage(
            id = ref.entity.providerId,
            blocks =
                listOfNotNull(
                    EntityHeader(
                        id = "header",
                        style = HeaderStyle.COVER,
                        entity = ref.entity,
                        title = playlist.name,
                        artwork = artwork(playlist.artworkPath),
                        details = listOf(text.trackCount(tracks.size)),
                        tracks = ref.entity,
                    ),
                    trackTable("tracks", null, tracks),
                ),
        )
    }

    /** A "Show all" list as one shelf per letter, year or month, [SHELVES_PER_PAGE] shelves per page. */
    fun all(
        ref: LocalRef.All,
        releases: List<LibraryReleaseRow>,
        groups: List<LibraryGroupRow>,
        page: Int,
    ): MetadataPage {
        val shelves: List<Pair<String, List<MetadataItem>>> =
            when (ref.section) {
                LocalSection.RECENT -> {
                    releases.groupBy { text.month(it.addedAtMs) }.map { (month, rows) -> month to rows.map(::releaseItem) }
                }

                LocalSection.RELEASES -> {
                    releases.groupBy { it.year?.toString() ?: text.unknownYear }.map { (year, rows) -> year to rows.map(::releaseItem) }
                }

                LocalSection.ARTISTS -> {
                    groups.groupBy { initial(it.name) }.map { (letter, rows) -> letter to rows.map(::artistItem) }
                }

                LocalSection.LABELS -> {
                    groups.groupBy { initial(it.name) }.map { (letter, rows) -> letter to rows.map(::labelItem) }
                }
            }
        val view = if (ref.section == LocalSection.ARTISTS) ItemView.ARTIST_PORTRAIT else ItemView.COVER_CARD
        val from = page * SHELVES_PER_PAGE
        return MetadataPage(
            id = ref.entity.providerId,
            blocks =
                shelves.drop(from).take(SHELVES_PER_PAGE).map { (title, items) ->
                    CollectionBlock(
                        id = "all:$title",
                        header = CollectionHeader(title),
                        layout = CollectionLayout.HORIZONTAL_SHELF,
                        defaultItemView = view,
                        items = items.map { it.copy(id = "all:$title:${it.id}") },
                    )
                },
            nextCursor = (page + 1).toString().takeIf { from + SHELVES_PER_PAGE < shelves.size },
        )
    }

    /** The queue item for a track row, as the player takes it from a table. */
    fun trackItem(
        row: LibraryTrackRow,
        ordinal: Int? = null,
    ): MetadataItem? {
        val playable = playableId(row) ?: return null
        val track = row.track
        val ref = EntityRef(EntityKind.TRACK, playable)
        val artists = credits(track.artist).map { ArtistCredit(it, LocalRef.Artist(it).entity) }
        val release = LocalRef.Release(track.releaseKey).entity
        val cover = artwork(row.artworkPath)
        return MetadataItem(
            id = track.id,
            entity = ref,
            title = track.title,
            subtitle =
                listOfNotNull(
                    track.artist.ifBlank { null },
                    track.bpm?.let(text.bpm),
                    track.musicalKey.ifBlank { null },
                ).joinToString(" · "),
            artwork = cover,
            view = ItemView.TRACK_ROW,
            artists = artists,
            durationSeconds = (track.durationMs / MILLIS_PER_SECOND).toInt().takeIf { it > 0 },
            ordinal = ordinal,
            album = track.album.ifBlank { null },
            track =
                TrackDescriptor(
                    ref = ref,
                    title = track.title,
                    artists = artists,
                    album = track.album.ifBlank { null },
                    albumRef = release,
                    durationMs = track.durationMs.takeIf { it > 0 },
                    artwork = cover,
                    trackNumber = track.trackNumber,
                    discNumber = track.discNumber,
                    year = track.year,
                    ids = mapOf(LocalCatalogProvider.ID to track.id),
                ),
        )
    }

    private fun collectionPage(
        ref: LocalRef,
        group: LibraryGroupRow,
        tracks: List<LibraryTrackRow>,
        releases: List<LibraryReleaseRow>,
        artists: List<LibraryGroupRow>,
        labels: List<LibraryGroupRow> = emptyList(),
    ): MetadataPage =
        MetadataPage(
            id = ref.entity.providerId,
            blocks =
                listOfNotNull(
                    EntityHeader(
                        id = "header",
                        style = HeaderStyle.COVER,
                        entity = ref.entity,
                        title = group.name,
                        artwork = artwork(group.artworkPath),
                        details = summary(group),
                        tracks = ref.entity,
                    ),
                    trackTable("tracks", null, tracks),
                    shelf("releases", text.releases, releases, null, ::releaseItem),
                    shelf("artists", text.artists, artists, null, ::artistItem, ItemView.ARTIST_PORTRAIT),
                    shelf("labels", text.labels, labels, null, ::labelItem),
                ),
        )

    private fun summary(group: LibraryGroupRow): List<String> =
        listOfNotNull(
            text.trackCount(group.trackCount),
            text.releaseCount(group.releaseCount),
            if (group.firstYear != null && group.lastYear != null && group.firstYear != group.lastYear) {
                text.yearRange(group.firstYear, group.lastYear)
            } else {
                group.lastYear?.toString()
            },
        )

    private fun trackTable(
        id: String,
        title: String?,
        rows: List<LibraryTrackRow>,
        ordinals: Boolean = false,
    ): CollectionBlock? {
        val items = rows.mapNotNull { row -> trackItem(row, row.track.trackNumber.takeIf { ordinals }) }
        if (items.isEmpty()) return null
        return CollectionBlock(
            id = id,
            header = title?.let(::CollectionHeader),
            layout = CollectionLayout.TRACK_TABLE,
            defaultItemView = ItemView.TRACK_ROW,
            items = items,
        )
    }

    private fun <T> shelf(
        id: String,
        title: String,
        rows: List<T>,
        showAll: LocalRef?,
        item: (T) -> MetadataItem,
        view: ItemView = ItemView.COVER_CARD,
        limit: Int = SHELF_SIZE,
    ): CollectionBlock? {
        if (rows.isEmpty()) return null
        return CollectionBlock(
            id = id,
            header = CollectionHeader(title),
            layout = CollectionLayout.HORIZONTAL_SHELF,
            defaultItemView = view,
            items = rows.take(limit).map { row -> item(row).let { it.copy(id = "$id:${it.id}") } },
            showAll = showAll?.entity?.takeIf { rows.size > limit },
        )
    }

    private fun releaseItem(row: LibraryReleaseRow): MetadataItem =
        MetadataItem(
            id = row.releaseKey,
            entity = LocalRef.Release(row.releaseKey).entity,
            title = row.title,
            subtitle = artistLine(credits(row.releaseArtist)),
            artwork = artwork(row.artworkPath),
            view = ItemView.COVER_CARD,
            details = listOfNotNull(row.year?.toString(), row.label.ifBlank { null }),
        )

    private fun artistItem(row: LibraryGroupRow): MetadataItem =
        MetadataItem(
            id = row.name,
            entity = LocalRef.Artist(row.name).entity,
            title = row.name,
            subtitle = text.trackCount(row.trackCount),
            artwork = artwork(row.artworkPath),
            view = ItemView.ARTIST_PORTRAIT,
        )

    private fun labelItem(row: LibraryGroupRow): MetadataItem =
        MetadataItem(
            id = row.name,
            entity = LocalRef.Label(row.name).entity,
            title = row.name,
            subtitle = text.releaseCount(row.releaseCount),
            artwork = artwork(row.artworkPath),
            view = ItemView.COVER_CARD,
        )

    private fun yearItem(row: LibraryGroupRow): MetadataItem =
        MetadataItem(
            id = row.name,
            entity = LocalRef.Year(row.name.toInt()).entity,
            title = row.name,
            subtitle = text.releaseCount(row.releaseCount),
            artwork = artwork(row.artworkPath),
            view = ItemView.COVER_CARD,
        )

    private fun playlistItem(row: LibraryPlaylistRow): MetadataItem =
        MetadataItem(
            id = row.id,
            entity = LocalRef.Playlist(row.id).entity,
            title = row.name,
            subtitle = text.trackCount(row.trackCount),
            artwork = artwork(row.artworkPath),
            view = ItemView.COVER_CARD,
        )

    /** A release credited to more than [MAX_NAMED_ARTISTS] artists is a compilation. */
    private fun artistLine(artists: List<String>): String =
        if (artists.size > MAX_NAMED_ARTISTS) text.variousArtists else artists.joinToString(LibraryIndexer.CREDIT_SEPARATOR)

    private fun credits(joined: String): List<String> = joined.split(LibraryIndexer.CREDIT_SEPARATOR).filter(String::isNotBlank)

    private fun artwork(path: String?): Artwork? = path?.let { Artwork(Uri.fromFile(File(it)).toString()) }

    private fun List<LibraryReleaseRow>.without(release: LibraryReleaseRow) = filter { it.releaseKey != release.releaseKey }

    private fun initial(name: String): String =
        name
            .firstOrNull()
            ?.uppercaseChar()
            ?.takeIf(Char::isLetter)
            ?.toString() ?: "#"

    companion object {
        const val SHELF_SIZE = 24
        const val SHELVES_PER_PAGE = 8
        private const val MAX_NAMED_ARTISTS = 3
        private const val MILLIS_PER_SECOND = 1_000L
    }
}

internal val LibraryReleaseRow.title: String get() = album.ifBlank { firstTitle }
