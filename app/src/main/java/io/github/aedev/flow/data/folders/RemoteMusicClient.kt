package io.github.aedev.flow.data.folders

import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

interface RemoteMusicFile : Closeable {
    val length: Long

    fun read(
        buffer: ByteArray,
        position: Long,
        offset: Int,
        length: Int,
    ): Int
}

class MusicFolderSecrets(
    val password: String = "",
    val privateKey: String = "",
)

/** Blocking network access to one kind of remote music folder. Callers run it on an interruptible IO dispatcher. */
interface RemoteMusicClient {
    /** Proves the folder is readable and returns it as it should be saved, e.g. with a newly trusted server key. */
    fun test(
        source: MusicFolder,
        secrets: MusicFolderSecrets,
    ): MusicFolder

    /** Directories and songs below [path]; also `.m3u` playlists when [includePlaylists] (the library index asks). */
    fun list(
        source: MusicFolder,
        secrets: MusicFolderSecrets,
        path: String,
        includePlaylists: Boolean = false,
    ): List<MusicFolderEntry>

    fun open(
        source: MusicFolder,
        secrets: MusicFolderSecrets,
        path: String,
    ): RemoteMusicFile
}

@Singleton
class RemoteMusicClients
    @Inject
    constructor(
        private val smb: SmbMusicClient,
        val webDav: WebDavMusicClient,
        private val sftp: SftpMusicClient,
        private val nfs: NfsMusicClient,
    ) {
        operator fun get(kind: MusicFolderKind): RemoteMusicClient =
            when (kind) {
                MusicFolderKind.LOCAL -> throw IllegalArgumentException("Local folders are read through the document provider")
                MusicFolderKind.SMB -> smb
                MusicFolderKind.WEBDAV -> webDav
                MusicFolderKind.SFTP -> sftp
                MusicFolderKind.NFS -> nfs
            }
    }
