package io.github.aedev.flow.data.folders

import android.net.Uri
import android.provider.DocumentsContract
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.data.music.model.MusicTrack
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class MusicFolderKind { LOCAL, SMB }

@Serializable
data class MusicFolder(
    val id: String = UUID.randomUUID().toString(),
    val revision: String = UUID.randomUUID().toString(),
    val name: String,
    val kind: MusicFolderKind,
    val treeUri: String = "",
    val host: String = "",
    val port: Int = 445,
    val share: String = "",
    val root: String = "",
    val username: String = "",
    val domain: String = "",
    val guest: Boolean = false,
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
                    host.isNotBlank() && host.all { it.isLetterOrDigit() || it in ".-:[]" } && !host.contains("://") &&
                        port in 1..65535 && share.isNotBlank() && share.none { it in "/\\\u0000" } &&
                        runCatching { smbPath("") }.isSuccess
                }
            }

    fun smbUri(relativePath: String): Uri =
        Uri
            .Builder()
            .scheme(SMB_SCHEME)
            .authority(id)
            .path("/" + safeFolderPath(relativePath))
            .appendQueryParameter("revision", revision)
            .build()

    fun smbPath(relativePath: String): String =
        listOf(safeFolderPath(root), safeFolderPath(relativePath)).filter(String::isNotEmpty).joinToString("\\").replace('/', '\\')

    companion object {
        const val SMB_SCHEME = "smbmusic"
    }
}

/** The URI the player reads the file at [location] in this folder through. */
fun MusicFolder.fileUri(location: String): Uri = if (kind == MusicFolderKind.SMB) smbUri(location) else Uri.parse(location)

internal fun safeFolderPath(path: String): String {
    val normalized = path.replace('\\', '/')
    require(!normalized.startsWith("//") && ':' !in normalized && '\u0000' !in normalized)
    val relative = normalized.removePrefix("/").removeSuffix("/")
    require(relative.isEmpty() || relative.split('/').all { it.isNotEmpty() && it != "." && it != ".." })
    return relative
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
