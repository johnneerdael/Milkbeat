package io.github.aedev.flow.data.folders

import android.net.Uri
import android.provider.DocumentsContract
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.UUID

@Serializable
enum class MusicFolderKind(
    val scheme: String,
    val defaultPort: Int,
) {
    LOCAL("content", 0),
    SMB("smbmusic", 445),
    WEBDAV("davmusic", 443),
    SFTP("sftpmusic", 22),
    NFS("nfsmusic", 2049),
    ;

    /** SMB reserves `:` for NTFS alternate data streams; the POSIX-backed protocols allow it in names. */
    val allowsColon: Boolean get() = this == WEBDAV || this == SFTP || this == NFS

    companion object {
        val remoteSchemes: Set<String> = entries.filter { it != LOCAL }.map { it.scheme }.toSet()

        fun forScheme(scheme: String?): MusicFolderKind? = entries.firstOrNull { it != LOCAL && it.scheme == scheme }
    }
}

@Serializable
enum class NfsVersion { AUTO, V3, V4, V4_1 }

@Serializable
data class MusicFolder(
    val id: String = UUID.randomUUID().toString(),
    val revision: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: MusicFolderKind,
    val treeUri: String = "",
    val host: String = "",
    val port: Int = kind.defaultPort,
    val share: String = "",
    val root: String = "",
    val username: String = "",
    val domain: String = "",
    val guest: Boolean = false,
    val url: String = "",
    val hostKey: String = "",
    val keyAuth: Boolean = false,
    val nfsVersion: NfsVersion = NfsVersion.AUTO,
    val uid: Int = 0,
    val gid: Int = 0,
) {
    fun isValid(): Boolean =
        name.isNotBlank() && id.isNotBlank() && revision.isNotBlank() &&
            when (kind) {
                MusicFolderKind.LOCAL -> {
                    runCatching {
                        Uri.parse(treeUri).scheme == "content" &&
                            DocumentsContract.isTreeUri(Uri.parse(treeUri))
                    }.getOrDefault(false)
                }

                MusicFolderKind.SMB -> {
                    validHost() && share.isNotBlank() && share.none { it in "/\\\u0000" } && runCatching { smbPath("") }.isSuccess
                }

                MusicFolderKind.WEBDAV -> {
                    webDavUrl() != null
                }

                MusicFolderKind.SFTP -> {
                    validHost() && username.isNotBlank() && runCatching { remotePath("") }.isSuccess
                }

                MusicFolderKind.NFS -> {
                    validHost() && uid >= 0 && gid >= 0 && runCatching { nfsExport() }.isSuccess &&
                        runCatching { remotePath("") }.isSuccess
                }
            }

    private fun validHost(): Boolean =
        host.isNotBlank() && host.all { it.isLetterOrDigit() || it in ".-:[]" } && !host.contains("://") && port in 1..65535

    fun remoteUri(relativePath: String): Uri {
        require(kind != MusicFolderKind.LOCAL)
        return Uri
            .Builder()
            .scheme(kind.scheme)
            .authority(id)
            .path("/" + folderPath(relativePath))
            .appendQueryParameter("revision", revision)
            .build()
    }

    /** [relativePath] validated with this kind's name rules. */
    fun folderPath(relativePath: String): String = safeFolderPath(relativePath, kind.allowsColon)

    fun childLocation(
        parent: String,
        name: String,
    ): String = childLocation(parent, name, kind.allowsColon)

    fun smbPath(relativePath: String): String =
        listOf(safeFolderPath(root), safeFolderPath(relativePath)).filter(String::isNotEmpty).joinToString("\\").replace('/', '\\')

    /**
     * Slash-separated server path for SFTP and NFS. An SFTP root without a leading slash is relative to the
     * login directory; NFS paths are always relative to [nfsExport].
     */
    fun remotePath(relativePath: String): String {
        val joined = listOf(folderPath(root), folderPath(relativePath)).filter(String::isNotEmpty).joinToString("/")
        return if (kind == MusicFolderKind.SFTP && root.trim().startsWith("/")) "/$joined" else joined
    }

    /** Absolute NFS export path, for example `/volume1/music`, or `/` for an NFSv4 pseudo-root. */
    fun nfsExport(): String = "/" + folderPath(share.trim().ifEmpty { "/" })

    /** The WebDAV collection URL, always ending in a slash, or null when [url] is not a plain http(s) URL. */
    fun webDavUrl(): HttpUrl? =
        url
            .trim()
            .toHttpUrlOrNull()
            ?.takeIf { it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null }
            ?.let { if (it.encodedPath.endsWith("/")) it else it.newBuilder().addPathSegment("").build() }
}

/** The URI the player reads the file at [location] in this folder through. */
fun MusicFolder.fileUri(location: String): Uri = if (kind == MusicFolderKind.LOCAL) Uri.parse(location) else remoteUri(location)

internal fun safeFolderPath(
    path: String,
    allowColon: Boolean = false,
): String {
    val normalized = path.replace('\\', '/')
    require(!normalized.startsWith("//") && (allowColon || ':' !in normalized) && '\u0000' !in normalized)
    val relative = normalized.removePrefix("/").removeSuffix("/")
    require(relative.isEmpty() || relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    return relative
}

internal fun childLocation(
    parent: String,
    name: String,
    allowColon: Boolean = false,
): String {
    require(name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\\' !in name)
    return safeFolderPath(listOf(safeFolderPath(parent, allowColon), name).filter(String::isNotEmpty).joinToString("/"), allowColon)
}

data class MusicFolderEntry(
    val name: String,
    val location: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
) {
    fun track(source: MusicFolder): MusicTrack {
        require(!isDirectory)
        val uri = source.fileUri(location)
        return MusicTrack(
            videoId = LocalMediaIds.of(uri),
            title = name.substringBeforeLast('.', name),
            artist = source.name,
            album = source.name,
            thumbnailUrl = FolderAudioRef(source.id, source.revision, location, size, modified).uri().toString(),
            duration = 0,
        )
    }
}

internal fun isMusicFile(
    name: String,
    mime: String? = null,
): Boolean =
    mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in
        setOf("mp3", "flac", "m4a", "aac", "ogg", "opus", "wav", "aiff", "aif", "alac", "wma", "amr")

internal fun isPlaylistFile(name: String): Boolean = name.substringAfterLast('.', "").lowercase() in setOf("m3u", "m3u8")

/** Whether a listing keeps [name]: always its songs, and its playlists when the library index asks. */
internal fun isListedFile(
    name: String,
    mime: String?,
    includePlaylists: Boolean,
): Boolean = if (isPlaylistFile(name)) includePlaylists else isMusicFile(name, mime)
