package io.github.aedev.flow.data.library.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.R
import io.github.aedev.flow.data.catalog.CatalogPlayback
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.MusicFolderRepository
import io.github.aedev.flow.data.folders.fileUri
import io.github.aedev.flow.data.library.index.GroupKind
import io.github.aedev.flow.data.library.index.GroupOrder
import io.github.aedev.flow.data.library.index.LibraryDao
import io.github.aedev.flow.data.library.index.LibraryFilter
import io.github.aedev.flow.data.library.index.LibraryGroupRow
import io.github.aedev.flow.data.library.index.LibraryIndexer
import io.github.aedev.flow.data.library.index.LibraryQueries
import io.github.aedev.flow.data.library.index.LibraryScanJobs
import io.github.aedev.flow.data.library.index.ReleaseOrder
import io.github.aedev.flow.data.library.index.TrackOrder
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicArtist
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.plugin.catalog.NoMetadataPluginException
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.HomeRequest
import nl.neerdael.milkbeat.catalog.MetadataItem
import nl.neerdael.milkbeat.catalog.MetadataPage
import nl.neerdael.milkbeat.catalog.MetadataProvider
import nl.neerdael.milkbeat.catalog.ProviderAccount
import java.io.FileNotFoundException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/** The local library has no songs yet: its first scan is running, or the folders hold none. */
class LocalLibraryEmptyException(
    message: String,
) : Exception(message)

/**
 * The music folders as a catalog, built from the tags in the library index. It serves the same
 * pages a metadata plugin does, so the home, entity pages and track tables render it unchanged, and
 * its tracks play through the player's local-file path.
 */
@Singleton
class LocalCatalogProvider
    @Inject
    internal constructor(
        @param:ApplicationContext private val context: Context,
        private val dao: LibraryDao,
        private val folders: MusicFolderRepository,
        private val scans: LibraryScanJobs,
    ) : MetadataProvider,
        CatalogPlayback {
        override val id: String = ID

        /**
         * The index revision stands in for an account: a scan that changes the index swaps it, so pages
         * built from the old index reload as a sign-in would make them.
         */
        override val account: Flow<ProviderAccount> =
            dao
                .observeMeta(LibraryIndexer.META_REVISION)
                .map { ProviderAccount.SignedIn(key = "$ID:${it.orEmpty()}") }
                .distinctUntilChanged()

        val hasTracks: Flow<Boolean> = dao.observeTrackCount().map { it > 0 }.distinctUntilChanged()

        override suspend fun home(request: HomeRequest): Result<MetadataPage> =
            runCatching {
                withContext(PerformanceDispatcher.diskIO) {
                    val current = folders.folders.first()
                    if (current.isEmpty()) throw NoMetadataPluginException()
                    if (request.cursor != null) return@withContext MetadataPage(id = "local:home", blocks = emptyList())
                    if (dao.trackCount() == 0) throw LocalLibraryEmptyException(emptyMessage())
                    val genre = request.filterId
                    val filter = LibraryFilter(genre = genre)
                    val more = LocalCatalogPages.SHELF_SIZE + 1
                    pages(current).home(
                        LocalHomeContent(
                            genres = dao.groups(LibraryQueries.groups(GroupKind.GENRE, LibraryFilter(), GroupOrder.TRACKS)),
                            genre = genre,
                            recent = dao.releases(LibraryQueries.releases(filter, ReleaseOrder.RECENT, more)),
                            artists = dao.groups(LibraryQueries.groups(GroupKind.ARTIST, filter, GroupOrder.TRACKS, more)),
                            releases = dao.releases(LibraryQueries.releases(filter, ReleaseOrder.NEWEST, more)),
                            playlists = if (genre == null) dao.playlistRows() else emptyList(),
                            labels = dao.groups(LibraryQueries.groups(GroupKind.LABEL, filter, GroupOrder.TRACKS, more)),
                            years = dao.groups(LibraryQueries.groups(GroupKind.YEAR, filter, GroupOrder.NAME_DESCENDING)),
                        ),
                    )
                }
            }

        override suspend fun page(
            entity: EntityRef,
            cursor: String?,
        ): Result<MetadataPage> =
            runCatching {
                withContext(PerformanceDispatcher.diskIO) {
                    val ref = LocalRef.parse(entity) ?: throw FileNotFoundException(entity.providerId)
                    val pages = pages(folders.folders.first())
                    when (ref) {
                        is LocalRef.Release -> releasePage(pages, ref)
                        is LocalRef.Artist -> artistPage(pages, ref)
                        is LocalRef.Label -> labelPage(pages, ref)
                        is LocalRef.Year -> yearPage(pages, ref)
                        is LocalRef.Playlist -> playlistPage(pages, ref)
                        is LocalRef.All -> allPage(pages, ref, cursor?.toIntOrNull() ?: 0)
                    }
                }
            }

        override fun track(item: MetadataItem): MusicTrack? {
            val descriptor = item.track ?: return null
            if (!LocalMediaIds.isLocal(descriptor.ref.providerId)) return null
            return MusicTrack(
                videoId = descriptor.ref.providerId,
                title = descriptor.title,
                artist = descriptor.artists.joinToString(LibraryIndexer.CREDIT_SEPARATOR) { it.name },
                thumbnailUrl = descriptor.artwork?.url.orEmpty(),
                duration = ((descriptor.durationMs ?: 0L) / MILLIS_PER_SECOND).toInt(),
                album = descriptor.album.orEmpty(),
                albumId = descriptor.albumRef?.providerId,
                artists = descriptor.artists.map { MusicArtist(it.name, it.entity?.providerId) },
            )
        }

        private suspend fun releasePage(
            pages: LocalCatalogPages,
            ref: LocalRef.Release,
        ): MetadataPage {
            val filter = LibraryFilter(releaseKey = ref.key)
            val release = dao.releases(LibraryQueries.releases(filter, ReleaseOrder.NEWEST)).firstOrNull() ?: throw missing(ref)
            val soleArtist = release.releaseArtist.split(LibraryIndexer.CREDIT_SEPARATOR).singleOrNull()
            val shelf = LocalCatalogPages.SHELF_SIZE + 2
            return pages.release(
                release = release,
                tracks = dao.tracks(LibraryQueries.tracks(filter, TrackOrder.RELEASE)),
                moreFromLabel =
                    release.label
                        .takeIf { it.isNotBlank() }
                        ?.let { label ->
                            dao.releases(LibraryQueries.releases(LibraryFilter(label = label), ReleaseOrder.NEWEST, shelf))
                        }.orEmpty(),
                moreFromArtist =
                    soleArtist
                        ?.let { artist ->
                            dao.releases(LibraryQueries.releases(LibraryFilter(artist = artist), ReleaseOrder.NEWEST, shelf))
                        }.orEmpty(),
            )
        }

        private suspend fun artistPage(
            pages: LocalCatalogPages,
            ref: LocalRef.Artist,
        ): MetadataPage {
            val filter = LibraryFilter(artist = ref.name)
            val artist = group(GroupKind.ARTIST, filter, ref.name) ?: throw missing(ref)
            return pages.artist(
                artist = artist,
                tracks = dao.tracks(LibraryQueries.tracks(filter, TrackOrder.NEWEST)),
                releases = dao.releases(LibraryQueries.releases(filter, ReleaseOrder.NEWEST)),
                labels = dao.groups(LibraryQueries.groups(GroupKind.LABEL, filter, GroupOrder.TRACKS)),
            )
        }

        private suspend fun labelPage(
            pages: LocalCatalogPages,
            ref: LocalRef.Label,
        ): MetadataPage {
            val filter = LibraryFilter(label = ref.name)
            val label = group(GroupKind.LABEL, filter, ref.name) ?: throw missing(ref)
            return pages.label(
                label = label,
                tracks = dao.tracks(LibraryQueries.tracks(filter, TrackOrder.NEWEST)),
                releases = dao.releases(LibraryQueries.releases(filter, ReleaseOrder.NEWEST)),
                artists = dao.groups(LibraryQueries.groups(GroupKind.ARTIST, filter, GroupOrder.TRACKS)),
            )
        }

        private suspend fun yearPage(
            pages: LocalCatalogPages,
            ref: LocalRef.Year,
        ): MetadataPage {
            val filter = LibraryFilter(year = ref.year)
            val year = group(GroupKind.YEAR, filter, ref.year.toString()) ?: throw missing(ref)
            return pages.year(
                year = year,
                tracks = dao.tracks(LibraryQueries.tracks(filter, TrackOrder.NEWEST)),
                releases = dao.releases(LibraryQueries.releases(filter, ReleaseOrder.NEWEST)),
                labels = dao.groups(LibraryQueries.groups(GroupKind.LABEL, filter, GroupOrder.TRACKS)),
            )
        }

        private suspend fun playlistPage(
            pages: LocalCatalogPages,
            ref: LocalRef.Playlist,
        ): MetadataPage {
            val playlist = dao.playlistRows().firstOrNull { it.id == ref.id } ?: throw missing(ref)
            return pages.playlist(playlist, dao.tracks(LibraryQueries.tracks(LibraryFilter(playlistId = ref.id), TrackOrder.PLAYLIST)))
        }

        private suspend fun allPage(
            pages: LocalCatalogPages,
            ref: LocalRef.All,
            page: Int,
        ): MetadataPage {
            val filter = LibraryFilter(genre = ref.genre)
            return when (ref.section) {
                LocalSection.RECENT -> {
                    pages.all(ref, dao.releases(LibraryQueries.releases(filter, ReleaseOrder.RECENT)), emptyList(), page)
                }

                LocalSection.RELEASES -> {
                    pages.all(ref, dao.releases(LibraryQueries.releases(filter, ReleaseOrder.NEWEST)), emptyList(), page)
                }

                LocalSection.ARTISTS -> {
                    pages.all(ref, emptyList(), dao.groups(LibraryQueries.groups(GroupKind.ARTIST, filter, GroupOrder.NAME)), page)
                }

                LocalSection.LABELS -> {
                    pages.all(ref, emptyList(), dao.groups(LibraryQueries.groups(GroupKind.LABEL, filter, GroupOrder.NAME)), page)
                }
            }
        }

        private suspend fun group(
            kind: GroupKind,
            filter: LibraryFilter,
            name: String,
        ): LibraryGroupRow? = dao.groups(LibraryQueries.groups(kind, filter, GroupOrder.TRACKS, limit = 1, named = name)).firstOrNull()

        private suspend fun emptyMessage(): String =
            if (scans.observe().first().running) {
                context.getString(R.string.local_library_indexing)
            } else {
                context.getString(R.string.local_library_empty)
            }

        private fun pages(current: List<MusicFolder>): LocalCatalogPages {
            val byId = current.associateBy { it.id }
            return LocalCatalogPages(text()) { row -> byId[row.track.folderId]?.let { LocalMediaIds.of(it.fileUri(row.track.location)) } }
        }

        private fun text(): LocalCatalogText {
            val resources = context.resources
            val monthFormat = DateTimeFormatter.ofPattern("LLLL yyyy", Locale.getDefault())
            return LocalCatalogText(
                recentlyAdded = context.getString(R.string.local_library_recently_added),
                artists = context.getString(R.string.local_library_artists),
                releases = context.getString(R.string.local_library_releases),
                playlists = context.getString(R.string.local_library_playlists),
                labels = context.getString(R.string.local_library_labels),
                years = context.getString(R.string.local_library_years),
                tracks = context.getString(R.string.local_library_tracks),
                variousArtists = context.getString(R.string.local_library_various_artists),
                unknownYear = context.getString(R.string.local_library_unknown_year),
                moreFrom = { context.getString(R.string.local_library_more_from, it) },
                bpm = { context.getString(R.string.local_library_bpm, it) },
                trackCount = { resources.getQuantityString(R.plurals.local_library_track_count, it, it) },
                releaseCount = { resources.getQuantityString(R.plurals.local_library_release_count, it, it) },
                yearRange = { first, last -> context.getString(R.string.local_library_year_range, first, last) },
                month = { ms -> monthFormat.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())) },
            )
        }

        private fun missing(ref: LocalRef) = FileNotFoundException(ref.entity.providerId)

        companion object {
            const val ID = "local"
            private const val MILLIS_PER_SECOND = 1_000L
        }
    }
