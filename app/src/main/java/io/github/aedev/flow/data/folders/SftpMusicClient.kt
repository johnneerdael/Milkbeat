package io.github.aedev.flow.data.folders

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.Response
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.UserAuthException
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthMethod
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import net.schmizz.sshj.userauth.method.PasswordResponseProvider
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

class SftpMusicClient
    @Inject
    constructor() : RemoteMusicClient {
        private fun connect(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            verifier: SftpHostKeyVerifier,
        ): SftpConnection {
            SftpSecurity.install()
            val config =
                DefaultConfig().apply { timeoutMs = TIMEOUT_MS }
            val ssh = SSHClient(config)
            try {
                ssh.connectTimeout = TIMEOUT_MS
                ssh.addHostKeyVerifier(verifier)
                try {
                    ssh.connect(source.host.removeSurrounding("[", "]"), source.port)
                } catch (error: TransportException) {
                    if (verifier.pinned != null && error.disconnectReason == DisconnectReason.HOST_KEY_NOT_VERIFIABLE) {
                        throw SftpHostKeyMismatchException(error)
                    }
                    throw error
                }
                ssh.auth(source.username, authMethods(ssh, source, secrets))
                val sftp = ssh.newSFTPClient()
                sftp.sftpEngine.timeoutMs = TIMEOUT_MS
                return SftpConnection(ssh, sftp)
            } catch (error: Exception) {
                runCatching { ssh.close() }
                throw error
            }
        }

        override fun test(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
        ): MusicFolder {
            require(source.kind == MusicFolderKind.SFTP && source.isValid())
            val trustOnFirstUse = source.hostKey.isBlank()
            val verifier = SftpHostKeyVerifier(source.hostKey.takeUnless { trustOnFirstUse })
            connect(source, secrets, verifier).use { connection ->
                val root = serverPath(source, "")
                if (connection.sftp.stat(root).type != FileMode.Type.DIRECTORY) throw IOException("Not a directory")
                connection.sftp.sftpEngine
                    .openDir(root)
                    .close()
            }
            return if (trustOnFirstUse) source.copy(hostKey = checkNotNull(verifier.presented)) else source
        }

        override fun list(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
            includePlaylists: Boolean,
        ): List<MusicFolderEntry> {
            require(source.kind == MusicFolderKind.SFTP && source.isValid() && source.hostKey.isNotBlank())
            return connect(source, secrets, SftpHostKeyVerifier(source.hostKey)).use { connection ->
                buildList {
                    for (info in connection.sftp.ls(serverPath(source, path))) {
                        if (Thread.currentThread().isInterrupted) throw InterruptedException()
                        val name = info.name
                        if (name == "." || name == "..") continue
                        val location = runCatching { source.childLocation(path, name) }.getOrNull() ?: continue
                        val attributes =
                            if (info.attributes.type == FileMode.Type.SYMLINK) {
                                try {
                                    connection.sftp.stat(serverPath(source, location))
                                } catch (_: SFTPException) {
                                    continue
                                }
                            } else {
                                info.attributes
                            }
                        sftpEntry(name, location, attributes, includePlaylists)?.let(::add)
                    }
                }
            }
        }

        override fun open(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
        ): RemoteMusicFile {
            require(source.kind == MusicFolderKind.SFTP && source.isValid() && source.hostKey.isNotBlank())
            require(source.folderPath(path).isNotEmpty())
            val remotePath = serverPath(source, path)
            return SftpRemoteFile {
                val connection = connect(source, secrets, SftpHostKeyVerifier(source.hostKey))
                try {
                    connection to connection.sftp.open(remotePath)
                } catch (error: Exception) {
                    connection.close()
                    throw error
                }
            }
        }

        private class SftpConnection(
            val ssh: SSHClient,
            val sftp: SFTPClient,
        ) : Closeable {
            override fun close() {
                try {
                    sftp.close()
                } finally {
                    ssh.close()
                }
            }
        }

        /**
         * Holds no heartbeat while playback is paused; a connection the server or a NAT dropped in the meantime
         * is reopened on the next read instead.
         */
        private class SftpRemoteFile(
            private val reopen: () -> Pair<SftpConnection, RemoteFile>,
        ) : RemoteMusicFile {
            private lateinit var connection: SftpConnection
            private lateinit var file: RemoteFile

            init {
                attach()
            }

            override val length: Long = file.length()
            private var nextPosition = -1L
            private var readAhead: InputStream? = null

            override fun read(
                buffer: ByteArray,
                position: Long,
                offset: Int,
                length: Int,
            ): Int {
                if (length == 0) return 0
                if (position >= this.length) return -1
                if (!connection.ssh.isConnected) reattach()
                val count =
                    try {
                        readAt(buffer, position, offset, length)
                    } catch (error: IOException) {
                        if (Thread.currentThread().isInterrupted || !isSftpReconnectable(error)) throw error
                        reattach()
                        readAt(buffer, position, offset, length)
                    }
                if (count > 0) nextPosition = position + count
                return if (count > 0) count else -1
            }

            private fun readAt(
                buffer: ByteArray,
                position: Long,
                offset: Int,
                length: Int,
            ): Int =
                // A contiguous follow-up read means sequential playback, so pipeline requests instead of paying a round trip per chunk.
                if (position == nextPosition) {
                    val stream = readAhead ?: file.ReadAheadRemoteFileInputStream(READ_AHEAD_REQUESTS, position).also { readAhead = it }
                    stream.read(buffer, offset, length)
                } else {
                    readAhead = null
                    file.read(position, buffer, offset, length)
                }

            private fun attach() {
                val (opened, remote) = reopen()
                connection = opened
                file = remote
            }

            private fun reattach() {
                readAhead = null
                nextPosition = -1
                runCatching { close() }
                attach()
            }

            override fun close() {
                try {
                    file.close()
                } finally {
                    connection.close()
                }
            }
        }

        companion object {
            private const val TIMEOUT_MS = 15_000
            private const val READ_AHEAD_REQUESTS = 4
        }
    }

internal fun serverPath(
    source: MusicFolder,
    relative: String,
): String = source.remotePath(relative).ifEmpty { "." }

internal fun sftpEntry(
    name: String,
    location: String,
    attributes: FileAttributes,
    includePlaylists: Boolean = false,
): MusicFolderEntry? {
    val directory = attributes.type == FileMode.Type.DIRECTORY
    val file = attributes.type == FileMode.Type.REGULAR || attributes.type == FileMode.Type.UNKNOWN
    if (!directory && !(file && isListedFile(name, null, includePlaylists))) return null
    return MusicFolderEntry(name, location, directory, if (directory) 0 else attributes.size, attributes.mtime * 1000)
}

internal fun authMethods(
    ssh: SSHClient,
    source: MusicFolder,
    secrets: MusicFolderSecrets,
): List<AuthMethod> {
    val passwordFinder =
        object : PasswordFinder {
            override fun reqPassword(resource: Resource<*>?): CharArray = secrets.password.toCharArray()

            override fun shouldRetry(resource: Resource<*>?): Boolean = false
        }
    return if (source.keyAuth) {
        require(secrets.privateKey.isNotBlank())
        val passphrase = passwordFinder.takeIf { secrets.password.isNotEmpty() }
        listOf(AuthPublickey(ssh.loadKeys(secrets.privateKey, null, passphrase)))
    } else {
        listOf(AuthPassword(passwordFinder), AuthKeyboardInteractive(PasswordResponseProvider(passwordFinder)))
    }
}

/** Whether a failed read may succeed on a fresh connection: not when the server answered with an SFTP status. */
internal fun isSftpReconnectable(error: IOException): Boolean =
    when (error) {
        is SftpHostKeyMismatchException, is UserAuthException -> false
        is SFTPException -> error.statusCode == Response.StatusCode.UNKNOWN
        else -> true
    }
