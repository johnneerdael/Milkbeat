package io.github.aedev.flow.data.library.index

import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.MusicFolderEntry
import io.github.aedev.flow.data.folders.MusicFolderRepository
import io.github.aedev.flow.data.folders.fileUri
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/** The music folders as the indexer walks them: their listings, and each file's tags or text. */
internal interface LibrarySources {
    suspend fun folders(): List<MusicFolder>

    suspend fun list(
        folder: MusicFolder,
        location: String,
    ): List<MusicFolderEntry>

    suspend fun tags(
        folder: MusicFolder,
        location: String,
    ): LibraryFileTags

    suspend fun text(
        folder: MusicFolder,
        location: String,
    ): String
}

internal class FolderLibrarySources
    @Inject
    constructor(
        private val repository: MusicFolderRepository,
        private val reader: LibraryFileReader,
    ) : LibrarySources {
        override suspend fun folders(): List<MusicFolder> = repository.folders.first()

        override suspend fun list(
            folder: MusicFolder,
            location: String,
        ): List<MusicFolderEntry> = repository.list(folder, location, includePlaylists = true)

        override suspend fun tags(
            folder: MusicFolder,
            location: String,
        ): LibraryFileTags = reader.tags(folder.fileUri(location))

        override suspend fun text(
            folder: MusicFolder,
            location: String,
        ): String = reader.text(folder.fileUri(location))
    }

/** The folder-relative path of a file found by walking from the folder's root. */
internal fun childPath(
    parent: String,
    name: String,
): String = if (parent.isEmpty()) name else "$parent/$name"

internal fun libraryTrackId(
    folderId: String,
    location: String,
): String = "$folderId|$location"
