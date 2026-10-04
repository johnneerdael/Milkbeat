package io.github.aedev.flow.data.folders

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * NFSv3/v4.x in userspace through nfs4j's XDR types and oncrpc4j. Connections are opened per operation; an
 * open file keeps its connection and reconnects lazily on its next read when the server dropped the idle TCP
 * connection or expired the NFSv4.1 lease, so paused playback needs no keep-alive traffic.
 */
class NfsMusicClient
    @Inject
    constructor() : RemoteMusicClient {
        private val learnedDialects = ConcurrentHashMap<String, NfsDialect>()

        override fun test(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
        ): MusicFolder {
            connect(source, "").use { (session, root) ->
                requireDirectory(root)
                session.list(root) { false }
            }
            return source
        }

        override fun list(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
            includePlaylists: Boolean,
        ): List<MusicFolderEntry> =
            connect(source, path).use { (session, directory) ->
                requireDirectory(directory)
                buildList {
                    session.list(directory) { entry ->
                        nfsMusicEntry(path, entry, includePlaylists)?.let(::add)
                        true
                    }
                }
            }

        override fun open(
            source: MusicFolder,
            secrets: MusicFolderSecrets,
            path: String,
        ): RemoteMusicFile {
            val opened = connect(source, path)
            if (opened.node.attributes.type != NfsFileType.REGULAR) {
                opened.close()
                throw IOException("Not a regular file")
            }
            return NfsMusicFile(opened) { connect(source, path) }
        }

        private fun connect(
            source: MusicFolder,
            path: String,
        ): NfsConnection {
            require(source.kind == MusicFolderKind.NFS && source.isValid())
            nfsComponents(source, path)
            val server = "${source.host}:${source.port}"
            return firstSupportedDialect(nfsDialects(source.nfsVersion, learnedDialects[server])) { dialect ->
                open(source, path, dialect).also { if (source.nfsVersion == NfsVersion.AUTO) learnedDialects[server] = dialect }
            }
        }

        private fun open(
            source: MusicFolder,
            path: String,
            dialect: NfsDialect,
        ): NfsConnection {
            val session =
                when (dialect) {
                    NfsDialect.V3 -> Nfs3Session.open(source)
                    NfsDialect.V4_0 -> Nfs4Session.open(source, minorVersion = 0)
                    NfsDialect.V4_1 -> Nfs4Session.open(source, minorVersion = 1)
                }
            try {
                val components = if (dialect == NfsDialect.V3) nfsComponents(source, path) else nfs4Components(source, path)
                return NfsConnection(session, session.lookup(components))
            } catch (error: Throwable) {
                session.close()
                throw error
            }
        }

        private fun requireDirectory(node: NfsNode) {
            if (node.attributes.type != NfsFileType.DIRECTORY) throw IOException("Not a directory")
        }
    }

private data class NfsConnection(
    val session: NfsSession,
    val node: NfsNode,
) : AutoCloseable {
    override fun close() {
        session.close()
    }
}

private class NfsMusicFile(
    opened: NfsConnection,
    private val reconnect: () -> NfsConnection,
) : RemoteMusicFile {
    private var connection: NfsConnection? = opened
    override val length: Long = opened.node.attributes.size

    override fun read(
        buffer: ByteArray,
        position: Long,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (position >= this.length) return -1
        val current = connection
        if (current != null) {
            try {
                return current.session.read(current.node, position, buffer, offset, length)
            } catch (error: Exception) {
                // After a timeout or interrupt the request may still run on the server, so the connection
                // (and its NFSv4.1 slot sequence) is never reused; the next read starts a fresh one.
                connection = null
                runCatching { current.close() }
                if (error !is IOException || !isNfsReconnectable(error)) throw error
            }
        }
        val fresh = reconnect()
        connection = fresh
        return fresh.session.read(fresh.node, position, buffer, offset, length)
    }

    override fun close() {
        val current = connection
        connection = null
        current?.close()
    }
}
