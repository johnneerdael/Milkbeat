package io.github.aedev.flow.data.library.index

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.ParserException
import androidx.media3.common.util.UnstableApi
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.isPlaylistFile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

internal data class LibraryScanProgress(
    val read: Int,
    val total: Int,
)

/** Whether a scan changed the index, and whether it could list every folder. */
internal data class LibraryScanResult(
    val changed: Boolean,
    val complete: Boolean,
)

/**
 * Brings the index up to date with the music folders. Only files that are new or whose size or time
 * changed are read; a folder that cannot be listed keeps what was indexed from it, so a NAS that is
 * asleep does not empty the library. Reads are written in batches, so an interrupted scan resumes
 * where it stopped.
 */
@OptIn(UnstableApi::class)
internal class LibraryIndexer
    @Inject
    constructor(
        private val sources: LibrarySources,
        private val dao: LibraryDao,
        private val artwork: LibraryArtworkStore,
    ) {
        private class FoundFile(
            val location: String,
            val path: String,
            val name: String,
            val size: Long,
            val modified: Long,
        )

        private class FolderPlan(
            val folder: MusicFolder,
            val toRead: List<FoundFile>,
            val updated: Set<String>,
            val playlists: List<FoundFile>,
            val tracksChanged: Boolean,
        )

        private class ReadFile(
            val track: LibraryTrackEntity?,
            val artists: List<String>,
            val tags: LibraryFileTags?,
            val updated: Boolean,
        )

        suspend fun scan(onProgress: suspend (LibraryScanProgress) -> Unit = {}): LibraryScanResult {
            val folders = sources.folders()
            val folderIds = folders.map { it.id }
            dao.deleteTracksOutside(folderIds)
            dao.deletePlaylistsOutside(folderIds)
            val wasEmpty = dao.trackCount() == 0
            var changed = false
            var complete = true

            val plans =
                folders.mapNotNull { folder ->
                    val files =
                        walk(folder) ?: run {
                            complete = false
                            return@mapNotNull null
                        }
                    val stamps = dao.trackStamps(folder.id).associateBy { it.id }
                    val songs = files.filterNot { isPlaylistFile(it.name) }
                    val found = songs.mapTo(HashSet()) { libraryTrackId(folder.id, it.location) }
                    val stale = stamps.keys - found
                    stale.chunked(DELETE_BATCH).forEach { dao.deleteTracks(it) }
                    val toRead =
                        songs.filter { file ->
                            val stamp = stamps[libraryTrackId(folder.id, file.location)]
                            stamp == null || stamp.sizeBytes != file.size || stamp.modifiedMs != file.modified
                        }
                    if (stale.isNotEmpty()) changed = true
                    FolderPlan(
                        folder = folder,
                        toRead = toRead,
                        updated =
                            toRead.mapNotNullTo(
                                HashSet(),
                            ) { file -> libraryTrackId(folder.id, file.location).takeIf { it in stamps } },
                        playlists = files.filter { isPlaylistFile(it.name) },
                        tracksChanged = stale.isNotEmpty() || toRead.isNotEmpty(),
                    )
                }

            val total = plans.sumOf { it.toRead.size }
            var read = 0
            onProgress(LibraryScanProgress(read, total))
            val artworkKeys = dao.artworkKeys().toHashSet()
            val coveredThisScan = HashSet<String>()
            val readThisScan = HashSet<String>()
            val retaggedWithoutCover = HashSet<String>()
            var announced = !wasEmpty
            for (plan in plans) {
                for (batch in plan.toRead.chunked(WRITE_BATCH)) {
                    val reads =
                        batch.chunked(PARALLEL_READS).flatMap { round ->
                            coroutineScope { round.map { async { read(plan, it) } }.awaitAll() }
                        }
                    val files = reads.filter { it.track != null }
                    // Covers go first: a scan cancelled after the tracks are saved would never read those files again.
                    for (file in files) {
                        val tags = file.tags ?: continue
                        val bytes = tags.artwork ?: continue
                        val key = checkNotNull(file.track).releaseKey
                        if (key in coveredThisScan || (key in artworkKeys && !file.updated)) continue
                        val previous = dao.artwork(key)?.path
                        val path = artwork.save(key, bytes, tags.artworkMimeType)
                        dao.upsertArtwork(LibraryArtworkEntity(key, path))
                        if (previous != null && previous != path) artwork.delete(previous)
                        coveredThisScan += key
                        artworkKeys += key
                    }
                    dao.saveTracks(
                        files.map { checkNotNull(it.track) },
                        files.flatMap { file ->
                            val id = checkNotNull(file.track).id
                            file.artists.mapIndexed { position, name -> LibraryTrackArtistEntity(id, position, name) }
                        },
                    )
                    for (file in files) {
                        val track = checkNotNull(file.track)
                        readThisScan += track.id
                        if (file.updated && file.tags?.artwork == null) retaggedWithoutCover += track.releaseKey
                    }
                    changed = true
                    read += files.size
                    onProgress(LibraryScanProgress(read, total))
                    if (!announced) {
                        // The first songs of a first scan show on the home at once instead of after every file is read.
                        bumpRevision()
                        announced = true
                    }
                }
                if (indexPlaylists(plan)) changed = true
            }

            // A cover is dropped only when every file of its release was read again and none carries one any more.
            for (key in retaggedWithoutCover - coveredThisScan) {
                val cover = dao.artwork(key) ?: continue
                if (dao.releaseTrackIds(key).all { it in readThisScan }) {
                    dao.deleteArtworkOf(key)
                    artwork.delete(cover.path)
                }
            }
            val orphans = dao.orphanArtwork()
            if (orphans.isNotEmpty()) {
                dao.deleteOrphanArtwork()
                orphans.forEach { artwork.delete(it.path) }
            }
            // Recorded even when a folder or file failed: the worker retries a missing folder a few times, and
            // a file left unread is read by the next scheduled scan, so neither turns every launch into a rescan.
            dao.setMeta(LibraryMetaEntity(META_SCANNED_FOLDERS, foldersFingerprint(folders)))
            dao.setMeta(LibraryMetaEntity(META_SCANNED_AT, System.currentTimeMillis().toString()))
            if (changed) bumpRevision()
            return LibraryScanResult(changed, complete)
        }

        private suspend fun walk(folder: MusicFolder): List<FoundFile>? =
            try {
                val files = mutableListOf<FoundFile>()
                val pending = ArrayDeque(listOf("" to ""))
                val visited = HashSet<String>()
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val (location, path) = pending.removeFirst()
                    if (!visited.add(location) || path.count { it == '/' } > MAX_DEPTH) continue
                    for (entry in sources.list(folder, location)) {
                        val childPath = childPath(path, entry.name)
                        if (entry.isDirectory) {
                            pending.addLast(entry.location to childPath)
                        } else {
                            files += FoundFile(entry.location, childPath, entry.name, entry.size, entry.modified)
                        }
                    }
                }
                files
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not list ${folder.name}; keeping what was indexed from it", e)
                null
            }

        private suspend fun read(
            plan: FolderPlan,
            file: FoundFile,
        ): ReadFile {
            val folder = plan.folder
            val id = libraryTrackId(folder.id, file.location)
            val tags =
                try {
                    sources.tags(folder, file.location)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ParserException) {
                    Log.w(TAG, "Unreadable tags in ${file.path}; indexing it by name", e)
                    null
                } catch (e: IOException) {
                    // Most likely the share dropped mid-scan: leave the file unindexed so the next scan reads it.
                    Log.w(TAG, "Could not read ${file.path}", e)
                    return ReadFile(track = null, artists = emptyList(), tags = null, updated = false)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not read the tags of ${file.path}", e)
                    null
                }
            val values = tags?.tags ?: LibraryTags()
            val artists = values.artists
            return ReadFile(
                track =
                    LibraryTrackEntity(
                        id = id,
                        folderId = folder.id,
                        location = file.location,
                        path = file.path,
                        sizeBytes = file.size,
                        modifiedMs = file.modified,
                        title = values.title.ifBlank { file.name.substringBeforeLast('.', file.name) },
                        artist = artists.joinToString(CREDIT_SEPARATOR),
                        album = values.album,
                        releaseKey = releaseKey(values, id),
                        releaseArtist = values.albumArtists.ifEmpty { artists }.joinToString(CREDIT_SEPARATOR),
                        genre = values.genre,
                        label = values.label,
                        catalogNumber = values.catalogNumber,
                        year = values.year,
                        trackNumber = values.trackNumber,
                        discNumber = values.discNumber,
                        bpm = values.bpm,
                        musicalKey = values.musicalKey,
                        energy = values.energy,
                        durationMs = tags?.durationMs ?: 0L,
                        addedAtMs = values.taggedAtMs ?: file.modified.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    ),
                artists = artists,
                tags = tags,
                updated = id in plan.updated,
            )
        }

        private suspend fun indexPlaylists(plan: FolderPlan): Boolean {
            val stored = dao.playlists(plan.folder.id).associateBy { it.id }
            val found = plan.playlists.associateBy { libraryTrackId(plan.folder.id, it.location) }
            val gone = stored.keys - found.keys
            gone.chunked(DELETE_BATCH).forEach { dao.deletePlaylists(it) }
            val toResolve =
                found.filter { (id, file) ->
                    val existing = stored[id]
                    plan.tracksChanged || existing == null || existing.sizeBytes != file.size || existing.modifiedMs != file.modified
                }
            if (toResolve.isEmpty()) return gone.isNotEmpty()
            val resolver = PlaylistResolver(dao.trackPaths(plan.folder.id).associate { it.path to it.id })
            for ((id, file) in toResolve) {
                val text =
                    try {
                        sources.text(plan.folder, file.location)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Could not read playlist ${file.path}", e)
                        continue
                    }
                dao.savePlaylist(
                    LibraryPlaylistEntity(
                        id = id,
                        folderId = plan.folder.id,
                        location = file.location,
                        path = file.path,
                        name = file.name.substringBeforeLast('.', file.name),
                        sizeBytes = file.size,
                        modifiedMs = file.modified,
                    ),
                    resolver.resolve(file.path, m3uEntries(text)),
                )
            }
            return true
        }

        private suspend fun bumpRevision() = dao.setMeta(LibraryMetaEntity(META_REVISION, UUID.randomUUID().toString()))

        companion object {
            const val META_REVISION = "revision"
            const val META_SCANNED_FOLDERS = "scannedFolders"
            const val META_SCANNED_AT = "scannedAt"
            const val CREDIT_SEPARATOR = ", "
            private const val TAG = "LibraryIndexer"
            private const val WRITE_BATCH = 48
            private const val PARALLEL_READS = 4
            private const val DELETE_BATCH = 500
            private const val MAX_DEPTH = 32

            /** Which folders, in which configuration, an index was built from. */
            fun foldersFingerprint(folders: List<MusicFolder>): String =
                folders.map { "${it.id}:${it.revision}" }.sorted().joinToString(",")
        }
    }

/**
 * The release a file belongs to. Beatport's release id is exact; without it the album is told apart
 * from another of the same name by its album artist (else its artists) and label. A file with no
 * album is its own single.
 */
internal fun releaseKey(
    tags: LibraryTags,
    trackId: String,
): String =
    when {
        tags.releaseId.isNotBlank() -> {
            "bp:${tags.releaseId.trim()}"
        }

        tags.album.isNotBlank() -> {
            "album:" +
                listOf(tags.album, tags.albumArtists.ifEmpty { tags.artists }.joinToString(","), tags.label)
                    .joinToString("|") { it.trim().lowercase() }
        }

        else -> {
            "track:$trackId"
        }
    }
