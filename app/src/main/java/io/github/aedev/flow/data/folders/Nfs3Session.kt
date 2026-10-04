package io.github.aedev.flow.data.folders

import org.dcache.nfs.nfsstat
import org.dcache.nfs.v3.xdr.GETATTR3args
import org.dcache.nfs.v3.xdr.GETATTR3res
import org.dcache.nfs.v3.xdr.LOOKUP3args
import org.dcache.nfs.v3.xdr.LOOKUP3res
import org.dcache.nfs.v3.xdr.READ3args
import org.dcache.nfs.v3.xdr.READ3res
import org.dcache.nfs.v3.xdr.READDIR3args
import org.dcache.nfs.v3.xdr.READDIR3res
import org.dcache.nfs.v3.xdr.READDIRPLUS3args
import org.dcache.nfs.v3.xdr.READDIRPLUS3res
import org.dcache.nfs.v3.xdr.cookie3
import org.dcache.nfs.v3.xdr.cookieverf3
import org.dcache.nfs.v3.xdr.count3
import org.dcache.nfs.v3.xdr.diropargs3
import org.dcache.nfs.v3.xdr.dirpath
import org.dcache.nfs.v3.xdr.fattr3
import org.dcache.nfs.v3.xdr.filename3
import org.dcache.nfs.v3.xdr.ftype3
import org.dcache.nfs.v3.xdr.mount_prot
import org.dcache.nfs.v3.xdr.mountres3
import org.dcache.nfs.v3.xdr.mountstat3
import org.dcache.nfs.v3.xdr.nfs3_prot
import org.dcache.nfs.v3.xdr.nfs_fh3
import org.dcache.nfs.v3.xdr.offset3
import org.dcache.nfs.v3.xdr.uint32
import org.dcache.nfs.v3.xdr.uint64
import org.dcache.oncrpc4j.portmap.OncRpcPortmap
import org.dcache.oncrpc4j.portmap.mapping
import org.dcache.oncrpc4j.rpc.RpcAuthType
import org.dcache.oncrpc4j.rpc.RpcAuthTypeNone
import org.dcache.oncrpc4j.rpc.net.IpProtocolType
import org.dcache.oncrpc4j.xdr.XdrAble
import org.dcache.oncrpc4j.xdr.XdrInt
import java.io.IOException

/** NFSv3 over TCP: MOUNT for the export handle, then LOOKUP/READDIRPLUS/READ on the NFS program. */
internal class Nfs3Session private constructor(
    private val nfs: NfsRpcConnection,
    private val auth: NfsAuthSys,
    private val root: ByteArray,
) : NfsSession {
    override fun lookup(components: List<String>): NfsNode {
        var node = NfsNode(root, getattr(root))
        for (name in components) {
            val result = LOOKUP3res()
            call(nfs3_prot.NFSPROC3_LOOKUP_3, LOOKUP3args().apply { what = dirop(node.handle, name) }, result)
            check(result.status, reachingExport = false)
            val handle = result.resok.`object`.data
            val attributes =
                result.resok.obj_attributes
                    .takeIf { it.attributes_follow }
                    ?.attributes
            node = NfsNode(handle, attributes?.let(::nfsAttributes) ?: getattr(handle))
        }
        return node
    }

    override fun list(
        directory: NfsNode,
        onEntry: (NfsDirEntry) -> Boolean,
    ) {
        var cookie = 0L
        var verifier = ByteArray(nfs3_prot.NFS3_COOKIEVERFSIZE)
        while (true) {
            val result = READDIRPLUS3res()
            call(
                nfs3_prot.NFSPROC3_READDIRPLUS_3,
                READDIRPLUS3args().apply {
                    dir = fh(directory.handle)
                    this.cookie = cookie3(uint64(cookie))
                    cookieverf = cookieverf3(verifier)
                    dircount = count3(uint32(DIRECTORY_COUNT))
                    maxcount = count3(uint32(LIST_REPLY_BYTES))
                },
                result,
            )
            if (result.status == nfsstat.NFSERR_NOTSUPP) return listWithoutAttributes(directory, onEntry)
            check(result.status, reachingExport = false)
            var entry = result.resok.reply.entries
            if (entry == null && !result.resok.reply.eof) throw IOException("NFS server returned an empty directory page")
            while (entry != null) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                cookie = entry.cookie.value.value
                val name = entry.name.value
                if (name != "." && name != "..") {
                    val attributes =
                        entry.name_attributes
                            .takeIf { it.attributes_follow }
                            ?.attributes
                            ?.let(::nfsAttributes)
                            ?: entry.name_handle
                                .takeIf { it.handle_follows }
                                ?.let { getattr(it.handle.data) }
                            ?: lookup(directory.handle, name)
                    if (!onEntry(NfsDirEntry(name, attributes))) return
                }
                entry = entry.nextentry
            }
            if (result.resok.reply.eof) return
            verifier = result.resok.cookieverf.value
        }
    }

    private fun listWithoutAttributes(
        directory: NfsNode,
        onEntry: (NfsDirEntry) -> Boolean,
    ) {
        var cookie = 0L
        var verifier = ByteArray(nfs3_prot.NFS3_COOKIEVERFSIZE)
        while (true) {
            val result = READDIR3res()
            call(
                nfs3_prot.NFSPROC3_READDIR_3,
                READDIR3args().apply {
                    dir = fh(directory.handle)
                    this.cookie = cookie3(uint64(cookie))
                    cookieverf = cookieverf3(verifier)
                    count = count3(uint32(LIST_REPLY_BYTES))
                },
                result,
            )
            check(result.status, reachingExport = false)
            var entry = result.resok.reply.entries
            if (entry == null && !result.resok.reply.eof) throw IOException("NFS server returned an empty directory page")
            while (entry != null) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                cookie = entry.cookie.value.value
                val name = entry.name.value
                if (name != "." && name != ".." && !onEntry(NfsDirEntry(name, lookup(directory.handle, name)))) return
                entry = entry.nextentry
            }
            if (result.resok.reply.eof) return
            verifier = result.resok.cookieverf.value
        }
    }

    override fun read(
        file: NfsNode,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val result = READ3res()
        call(
            nfs3_prot.NFSPROC3_READ_3,
            READ3args().apply {
                this.file = fh(file.handle)
                this.offset = offset3(uint64(position))
                count = count3(uint32(minOf(length, MAX_READ_BYTES)))
            },
            result,
        )
        check(result.status, reachingExport = false)
        val data = result.resok.data
        val count = minOf(result.resok.count.value.value, data.size, length)
        if (count <= 0) return -1
        System.arraycopy(data, 0, buffer, offset, count)
        return count
    }

    override fun close() {
        nfs.close()
    }

    private fun lookup(
        directory: ByteArray,
        name: String,
    ): NfsAttributes {
        val result = LOOKUP3res()
        call(nfs3_prot.NFSPROC3_LOOKUP_3, LOOKUP3args().apply { what = dirop(directory, name) }, result)
        check(result.status, reachingExport = false)
        return result.resok.obj_attributes
            .takeIf { it.attributes_follow }
            ?.attributes
            ?.let(::nfsAttributes)
            ?: getattr(result.resok.`object`.data)
    }

    private fun getattr(handle: ByteArray): NfsAttributes {
        val result = GETATTR3res()
        call(nfs3_prot.NFSPROC3_GETATTR_3, GETATTR3args().apply { `object` = fh(handle) }, result)
        check(result.status, reachingExport = handle.contentEquals(root))
        return nfsAttributes(result.resok.obj_attributes)
    }

    private fun call(
        procedure: Int,
        args: XdrAble,
        result: XdrAble,
    ) {
        retryWhileDelayed({ statusOf(result) }) {
            nfs.call(nfs3_prot.NFS_PROGRAM, nfs3_prot.NFS_V3, procedure, auth, args, result)
        }
    }

    companion object {
        private const val DEFAULT_NFS_PORT = 2049
        private const val PORTMAP_PROBE_TIMEOUT_MS = 5_000L
        private const val MAX_READ_BYTES = 64 * 1024
        private const val LIST_REPLY_BYTES = 32 * 1024
        private const val DIRECTORY_COUNT = 8 * 1024

        fun open(source: MusicFolder): Nfs3Session {
            val (mountPort, nfsPort) = ports(source)
            val auth = NfsAuthSys(source.uid, source.gid)
            val nfs = NfsRpcConnection.connect(source.host, nfsPort)
            try {
                val root =
                    if (mountPort == nfsPort) {
                        mount(nfs, source, auth)
                    } else {
                        NfsRpcConnection.connect(source.host, mountPort).use { mount(it, source, auth) }
                    }
                return Nfs3Session(nfs, auth, root)
            } catch (error: Throwable) {
                nfs.close()
                throw error
            }
        }

        private fun mount(
            connection: NfsRpcConnection,
            source: MusicFolder,
            auth: NfsAuthSys,
        ): ByteArray {
            val result = mountres3()
            connection.call(
                mount_prot.MOUNT_PROGRAM,
                mount_prot.MOUNT_V3,
                mount_prot.MOUNTPROC3_MNT_3,
                auth,
                dirpath(source.nfsExport()),
                result,
            )
            when (result.fhs_status) {
                mountstat3.MNT3_OK -> {
                    Unit
                }

                mountstat3.MNT3ERR_PERM, mountstat3.MNT3ERR_ACCES -> {
                    throw NfsInsecurePortRequiredException("NFS export refused the mount (status ${result.fhs_status})")
                }

                else -> {
                    throw nfsStatusException(result.fhs_status, reachingExport = false)
                }
            }
            val flavors = result.mountinfo.auth_flavors
            if (flavors != null && flavors.isNotEmpty() && RpcAuthType.UNIX !in flavors) {
                throw IOException("NFS export does not accept AUTH_SYS credentials")
            }
            return result.mountinfo.fhandle.value
        }

        /**
         * MOUNT and NFS ports from the portmapper, only when the folder uses the standard NFS port: a custom
         * port names one specific server (for example `rclone serve nfs`, which answers MOUNT on its NFS port)
         * that another server's portmapper on the same host must not override.
         */
        private fun ports(source: MusicFolder): Pair<Int, Int> {
            if (source.port != DEFAULT_NFS_PORT) return source.port to source.port
            val registered =
                try {
                    NfsRpcConnection.connect(source.host, OncRpcPortmap.PORTMAP_PORT, PORTMAP_PROBE_TIMEOUT_MS).use { portmap ->
                        getport(portmap, mount_prot.MOUNT_PROGRAM, mount_prot.MOUNT_V3) to
                            getport(portmap, nfs3_prot.NFS_PROGRAM, nfs3_prot.NFS_V3)
                    }
                } catch (error: IOException) {
                    0 to 0
                }
            return (registered.first.takeIf { it > 0 } ?: source.port) to (registered.second.takeIf { it > 0 } ?: source.port)
        }

        // PMAPPROC_GETPORT (v2) is answered by every rpcbind, returns a plain port and, unlike
        // GenericPortmapClient.getPort, can be bounded by a timeout.
        private fun getport(
            portmap: NfsRpcConnection,
            program: Int,
            version: Int,
        ): Int {
            val port = XdrInt()
            portmap.call(
                OncRpcPortmap.PORTMAP_PROGRAMM,
                OncRpcPortmap.PORTMAP_V2,
                OncRpcPortmap.PMAPPROC_GETPORT,
                RpcAuthTypeNone(),
                mapping(program, version, IpProtocolType.TCP, 0),
                port,
                PORTMAP_PROBE_TIMEOUT_MS,
            )
            return port.intValue()
        }

        private fun fh(handle: ByteArray) = nfs_fh3().apply { data = handle }

        private fun dirop(
            directory: ByteArray,
            name: String,
        ) = diropargs3().apply {
            dir = fh(directory)
            this.name = filename3(name)
        }

        private fun nfsAttributes(attributes: fattr3) =
            NfsAttributes(
                type =
                    when (attributes.type) {
                        ftype3.NF3DIR -> NfsFileType.DIRECTORY
                        ftype3.NF3REG -> NfsFileType.REGULAR
                        else -> NfsFileType.OTHER
                    },
                size = attributes.size.value,
                modified =
                    nfsTimeMillis(
                        attributes.mtime.seconds.value
                            .toUInt()
                            .toLong(),
                        attributes.mtime.nseconds.value
                            .toUInt()
                            .toLong(),
                    ),
            )

        private fun check(
            status: Int,
            reachingExport: Boolean,
        ) {
            if (status != nfsstat.NFS_OK) throw nfsStatusException(status, reachingExport)
        }

        private fun statusOf(result: XdrAble): Int =
            when (result) {
                is LOOKUP3res -> result.status
                is GETATTR3res -> result.status
                is READDIRPLUS3res -> result.status
                is READDIR3res -> result.status
                is READ3res -> result.status
                else -> nfsstat.NFS_OK
            }
    }
}
