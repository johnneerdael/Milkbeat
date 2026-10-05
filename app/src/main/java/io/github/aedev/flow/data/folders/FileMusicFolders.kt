package io.github.aedev.flow.data.folders

import android.net.Uri
import java.io.File
import java.io.FileNotFoundException

internal object FileMusicFolders {
    fun add(uri: Uri): MusicFolder {
        val directory = file(uri)
        if (!directory.isDirectory || !directory.canRead()) throw FileNotFoundException("Folder no longer available")
        return MusicFolder(
            name =
                directory.name.ifBlank {
                    directory.path
                },
            kind = MusicFolderKind.LOCAL,
            treeUri = Uri.fromFile(directory).toString(),
        )
    }

    fun resolve(
        source: MusicFolder,
        location: String,
    ): File {
        val root = file(Uri.parse(source.treeUri))
        val target = if (location.isEmpty()) root else file(Uri.parse(location))
        require(target.toPath().startsWith(root.toPath()))
        return target
    }

    fun list(
        source: MusicFolder,
        location: String,
        includePlaylists: Boolean,
    ): List<MusicFolderEntry> {
        val root = file(Uri.parse(source.treeUri))
        val directory = resolve(source, location)
        val children = directory.listFiles() ?: throw FileNotFoundException("Folder no longer available")
        return children.mapNotNull { child ->
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            val canonical = child.canonicalFile
            if (!canonical.toPath().startsWith(root.toPath())) return@mapNotNull null
            val directoryChild = child.isDirectory
            if (!directoryChild && (!child.isFile || !isListedFile(child.name, null, includePlaylists))) return@mapNotNull null
            MusicFolderEntry(child.name, Uri.fromFile(canonical).toString(), directoryChild, child.length(), child.lastModified())
        }
    }

    private fun file(uri: Uri): File {
        require(isFileFolderUri(uri))
        return File(requireNotNull(uri.path)).canonicalFile
    }
}

internal fun isFileFolderUri(uri: Uri): Boolean =
    uri.scheme == "file" && uri.authority.isNullOrEmpty() && uri.path?.startsWith('/') == true && uri.query == null && uri.fragment == null
