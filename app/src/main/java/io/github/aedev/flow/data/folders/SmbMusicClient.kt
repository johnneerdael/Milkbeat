package io.github.aedev.flow.data.folders

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.auth.NtlmAuthenticator
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

interface RemoteMusicFile : Closeable {
    val length: Long

    fun read(
        buffer: ByteArray,
        position: Long,
        offset: Int,
        length: Int,
    ): Int
}

class SmbMusicClient
    @Inject
    constructor() {
        private fun connect(
            source: MusicFolder,
            password: String,
        ): SmbConnection {
            require(source.kind == MusicFolderKind.SMB && source.isValid())
            val config =
                SmbConfig
                    .builder()
                    .withSocketFactory(ProxySocketFactory(TIMEOUT_MS))
                    .withTimeout(TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
                    // Request deadlines bound I/O; buffered or paused playback may leave the socket idle.
                    .withSoTimeout(0)
                    .withAuthenticators(NtlmAuthenticator.Factory())
                    .build()
            val client = SMBClient(config)
            var connection: Connection? = null
            var session: Session? = null
            try {
                connection = client.connect(source.host.removeSurrounding("[", "]"), source.port)
                val auth =
                    if (source.guest) {
                        AuthenticationContext.guest()
                    } else {
                        AuthenticationContext(
                            source.username,
                            password.toCharArray(),
                            source.domain,
                        )
                    }
                session =
                    try {
                        connection.authenticate(auth)
                    } finally {
                        auth.password.fill('\u0000')
                    }
                val share = session.connectShare(source.share)
                if (share !is DiskShare) {
                    share.close()
                    throw IOException("Not a disk share")
                }
                return SmbConnection(client, connection, session, share)
            } catch (error: Exception) {
                runCatching { session?.close() }
                runCatching { connection?.close() }
                client.close()
                throw error
            }
        }

        fun test(
            source: MusicFolder,
            password: String,
        ) {
            connect(source, password).use { connection ->
                connection.share
                    .openDirectory(
                        source.smbPath(""),
                        READ_DIRECTORY,
                        setOf(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                        SHARED_READ,
                        SMB2CreateDisposition.FILE_OPEN,
                        setOf(SMB2CreateOptions.FILE_DIRECTORY_FILE),
                    ).use {
                        it.iterator().hasNext()
                    }
            }
        }

        fun list(
            source: MusicFolder,
            password: String,
            path: String,
            includePlaylists: Boolean = false,
        ): List<MusicFolderEntry> =
            connect(source, password).use { connection ->
                connection.share
                    .openDirectory(
                        source.smbPath(path),
                        READ_DIRECTORY,
                        setOf(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                        SHARED_READ,
                        SMB2CreateDisposition.FILE_OPEN,
                        setOf(SMB2CreateOptions.FILE_DIRECTORY_FILE),
                    ).use { directory ->
                        buildList {
                            for (entry in directory) {
                                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                                val name = entry.fileName
                                if (name == "." || name == "..") continue
                                val folder = entry.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
                                if (!folder && !isListedFile(name, null, includePlaylists)) continue
                                val location = listOf(safeFolderPath(path), name).filter(String::isNotEmpty).joinToString("/")
                                safeFolderPath(location)
                                add(MusicFolderEntry(name, location, folder, entry.endOfFile, entry.lastWriteTime.toEpochMillis()))
                            }
                        }
                    }
            }

        fun open(
            source: MusicFolder,
            password: String,
            path: String,
        ): RemoteMusicFile {
            val remotePath = source.smbPath(path)
            val connection = connect(source, password)
            try {
                val file =
                    connection.share.openFile(
                        remotePath,
                        setOf(AccessMask.FILE_READ_DATA, AccessMask.FILE_READ_ATTRIBUTES),
                        setOf(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        SHARED_READ,
                        SMB2CreateDisposition.FILE_OPEN,
                        setOf(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
                    )
                return object : RemoteMusicFile {
                    override val length: Long get() = file.length

                    override fun read(
                        buffer: ByteArray,
                        position: Long,
                        offset: Int,
                        length: Int,
                    ): Int = file.read(buffer, position, offset, length)

                    override fun close() {
                        try {
                            file.close()
                        } finally {
                            connection.close()
                        }
                    }
                }
            } catch (error: Exception) {
                connection.close()
                throw error
            }
        }

        private class SmbConnection(
            val client: SMBClient,
            val connection: Connection,
            val session: Session,
            val share: DiskShare,
        ) : Closeable {
            override fun close() {
                try {
                    share.close()
                } finally {
                    try {
                        session.close()
                    } finally {
                        try {
                            connection.close()
                        } finally {
                            client.close()
                        }
                    }
                }
            }
        }

        companion object {
            private const val TIMEOUT_MS = 15_000
            private val READ_DIRECTORY = setOf(AccessMask.FILE_LIST_DIRECTORY, AccessMask.FILE_READ_ATTRIBUTES)
            private val SHARED_READ =
                setOf(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE, SMB2ShareAccess.FILE_SHARE_DELETE)
        }
    }
