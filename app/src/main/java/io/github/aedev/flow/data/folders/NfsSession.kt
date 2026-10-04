package io.github.aedev.flow.data.folders

import java.io.Closeable

/** One connected NFS protocol dialect. Used by one thread at a time. */
internal interface NfsSession : Closeable {
    /** Resolves path components below the export (NFSv3) or below the server root (NFSv4). */
    fun lookup(components: List<String>): NfsNode

    /** Visits every entry except `.` and `..`; stops early when [onEntry] returns false. */
    fun list(
        directory: NfsNode,
        onEntry: (NfsDirEntry) -> Boolean,
    )

    /** Reads at most [length] bytes, possibly fewer; -1 at end of file. */
    fun read(
        file: NfsNode,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int
}

internal enum class NfsFileType { DIRECTORY, REGULAR, OTHER }

internal class NfsAttributes(
    val type: NfsFileType,
    val size: Long,
    val modified: Long,
)

internal class NfsNode(
    val handle: ByteArray,
    val attributes: NfsAttributes,
)

internal class NfsDirEntry(
    val name: String,
    val attributes: NfsAttributes,
)
