package io.github.aedev.flow.data.folders

import org.dcache.nfs.nfsstat
import org.dcache.nfs.v4.CompoundBuilder
import org.dcache.nfs.v4.Stateids
import org.dcache.nfs.v4.xdr.COMPOUND4args
import org.dcache.nfs.v4.xdr.COMPOUND4res
import org.dcache.nfs.v4.xdr.channel_attrs4
import org.dcache.nfs.v4.xdr.clientid4
import org.dcache.nfs.v4.xdr.count4
import org.dcache.nfs.v4.xdr.fattr4
import org.dcache.nfs.v4.xdr.fattr4_size
import org.dcache.nfs.v4.xdr.fattr4_time_modify
import org.dcache.nfs.v4.xdr.fattr4_type
import org.dcache.nfs.v4.xdr.nfs4_prot
import org.dcache.nfs.v4.xdr.nfs_fh4
import org.dcache.nfs.v4.xdr.nfs_ftype4
import org.dcache.nfs.v4.xdr.nfs_opnum4
import org.dcache.nfs.v4.xdr.nfs_resop4
import org.dcache.nfs.v4.xdr.sessionid4
import org.dcache.nfs.v4.xdr.state_protect_how4
import org.dcache.nfs.v4.xdr.uint32_t
import org.dcache.nfs.v4.xdr.verifier4
import org.dcache.oncrpc4j.xdr.BadXdrOncRpcException
import org.dcache.oncrpc4j.xdr.Xdr
import java.io.IOException
import java.util.UUID

/**
 * NFSv4.0 or v4.1+ over TCP. Reads use the anonymous stateid, so no OPEN state (and for v4.0 no client ID)
 * is needed. A v4.1 session uses a single slot, which matches the one-thread-at-a-time contract.
 */
internal class Nfs4Session private constructor(
    private val connection: NfsRpcConnection,
    private val auth: NfsAuthSys,
    private val minorVersion: Int,
) : NfsSession {
    private var clientId: clientid4? = null
    private var sessionId: sessionid4? = null
    private var sequence = 0
    private var answered = false
    private var replyLimit = MAX_READ_BYTES + COMPOUND_OVERHEAD_BYTES
    private var maxOperations = MAX_OPERATIONS

    /** Long paths are resolved in several compounds so none exceeds the session's operation limit. */
    override fun lookup(components: List<String>): NfsNode {
        val chunks = components.chunked(maxOperations - LOOKUP_FIXED_OPERATIONS).ifEmpty { listOf(emptyList()) }
        var handle: nfs_fh4? = null
        for ((index, chunk) in chunks.withIndex()) {
            val last = index == chunks.lastIndex
            val start = handle
            val result =
                compound(reachingExport = true) {
                    (if (start == null) withPutrootfh() else withPutfh(start)).withLookup(chunk.joinToString("/")).withGetfh().also {
                        if (last) it.withGetattr(*ATTRIBUTES)
                    }
                }
            handle =
                result
                    .op(nfs_opnum4.OP_GETFH)
                    .opgetfh.resok4.`object`
            if (last) {
                return NfsNode(
                    handle.value,
                    attributes(
                        result
                            .op(nfs_opnum4.OP_GETATTR)
                            .opgetattr.resok4.obj_attributes,
                    ),
                )
            }
        }
        throw IllegalStateException("Lookup produced no compound")
    }

    override fun list(
        directory: NfsNode,
        onEntry: (NfsDirEntry) -> Boolean,
    ) {
        var cookie = 0L
        var verifier = verifier4(ByteArray(nfs4_prot.NFS4_VERIFIER_SIZE))
        val maxCount = minOf(LIST_REPLY_BYTES, replyLimit - COMPOUND_OVERHEAD_BYTES)
        while (true) {
            val readdir =
                compound {
                    withPutfh(nfs_fh4(directory.handle)).withReaddir(cookie, verifier, DIRECTORY_COUNT, maxCount, *ATTRIBUTES)
                }.op(nfs_opnum4.OP_READDIR).opreaddir.resok4
            var entry = readdir.reply.entries
            if (entry == null && !readdir.reply.eof) throw IOException("NFS server returned an empty directory page")
            while (entry != null) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                cookie = entry.cookie.value
                val name = entry.name.toString()
                if (name != "." && name != ".." && !onEntry(NfsDirEntry(name, attributes(entry.attrs)))) return
                entry = entry.nextentry
            }
            if (readdir.reply.eof) return
            verifier = readdir.cookieverf
        }
    }

    override fun read(
        file: NfsNode,
        position: Long,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val count = minOf(length, MAX_READ_BYTES, replyLimit - COMPOUND_OVERHEAD_BYTES)
        val data =
            compound {
                withPutfh(nfs_fh4(file.handle)).withRead(count, position, Stateids.ZeroStateId())
            }.op(nfs_opnum4.OP_READ).opread.resok4.data
        val read = minOf(data.remaining(), length)
        if (read <= 0) return -1
        data.get(buffer, offset, read)
        return read
    }

    override fun close() {
        try {
            val session = sessionId
            val client = clientId
            if (connection.isOpen && client != null) {
                if (session != null) runCatching { send(CompoundBuilder().withDestroysession(session), CLOSE_TIMEOUT_MS) }
                runCatching { send(CompoundBuilder().withDestroyclientid(client), CLOSE_TIMEOUT_MS) }
            }
        } finally {
            connection.close()
        }
    }

    private fun establishSession() {
        val exchange =
            send(
                CompoundBuilder().withExchangeId(
                    IMPLEMENTATION_DOMAIN,
                    IMPLEMENTATION_NAME,
                    // A fresh owner per connection: concurrent sessions from this app must not replace each other.
                    "milkbeat-" + UUID.randomUUID(),
                    0,
                    state_protect_how4.SP4_NONE,
                ),
                setup = true,
            ).op(nfs_opnum4.OP_EXCHANGE_ID).opexchange_id.eir_resok4
        clientId = exchange.eir_clientid
        val args =
            CompoundBuilder()
                .withCreatesession(
                    exchange.eir_clientid,
                    exchange.eir_sequenceid,
                ).withMinorversion(minorVersion)
                .build()
        // CompoundBuilder offers 8 KiB replies and 8192 slots; ask for what this client actually uses.
        args.argarray[0].opcreate_session.csa_fore_chan_attrs =
            channel_attrs4().apply {
                ca_headerpadsize = count4(0)
                ca_maxrequestsize = count4(REQUEST_BYTES)
                ca_maxresponsesize = count4(MAX_READ_BYTES + COMPOUND_OVERHEAD_BYTES)
                ca_maxresponsesize_cached = count4(CACHED_REPLY_BYTES)
                ca_maxoperations = count4(MAX_OPERATIONS)
                ca_maxrequests = count4(1)
                ca_rdma_ird = arrayOf<uint32_t>()
            }
        val created = call(args, setup = true).op(nfs_opnum4.OP_CREATE_SESSION).opcreate_session.csr_resok4
        sessionId = created.csr_sessionid
        replyLimit = created.csr_fore_chan_attrs.ca_maxresponsesize.value
        maxOperations = minOf(MAX_OPERATIONS, created.csr_fore_chan_attrs.ca_maxoperations.value)
        if (replyLimit <= COMPOUND_OVERHEAD_BYTES || maxOperations <= LOOKUP_FIXED_OPERATIONS) {
            throw IOException("NFS server session limits are too small")
        }
        sequence = 0
        val reclaim = sequenced(CompoundBuilder().withReclaimComplete())
        if (reclaim.status != nfsstat.NFS_OK && reclaim.status != nfsstat.NFSERR_COMPLETE_ALREADY) {
            throw nfsStatusException(reclaim.status, reachingExport = false)
        }
    }

    private fun compound(
        reachingExport: Boolean = false,
        ops: CompoundBuilder.() -> CompoundBuilder,
    ): COMPOUND4res {
        lateinit var result: COMPOUND4res
        retryWhileDelayed({ result.status }) {
            result = if (minorVersion == 0) send(CompoundBuilder().ops(), checked = false) else sequenced(CompoundBuilder().ops())
        }
        if (result.status != nfsstat.NFS_OK) throw nfsStatusException(result.status, reachingExport)
        return result
    }

    /** Sends [builder] behind SEQUENCE; the slot's sequence id only advances when SEQUENCE itself succeeded. */
    private fun sequenced(builder: CompoundBuilder): COMPOUND4res {
        val args =
            CompoundBuilder()
                .withMinorversion(minorVersion)
                .withSequence(false, checkNotNull(sessionId), sequence, 0, 0)
                .build()
        val body = builder.withMinorversion(minorVersion).build()
        args.argarray += body.argarray
        val result = rpc(args, NFS_TIMEOUT_MS)
        if (result.resarray
                .firstOrNull()
                ?.opsequence
                ?.sr_status == nfsstat.NFS_OK
        ) {
            sequence++
        }
        return result
    }

    private fun send(
        builder: CompoundBuilder,
        timeoutMs: Long = NFS_TIMEOUT_MS,
        checked: Boolean = true,
        setup: Boolean = false,
    ): COMPOUND4res = call(builder.withMinorversion(minorVersion).build(), timeoutMs, checked, setup)

    private fun call(
        args: COMPOUND4args,
        timeoutMs: Long = NFS_TIMEOUT_MS,
        checked: Boolean = true,
        setup: Boolean = false,
    ): COMPOUND4res {
        val result = rpc(args, timeoutMs)
        if (checked && result.status != nfsstat.NFS_OK) {
            // A v4.0-only server answers EXCHANGE_ID with MINOR_VERS_MISMATCH, or as an unknown operation.
            if (setup && result.status in setOf(nfsstat.NFSERR_NOTSUPP, nfsstat.NFSERR_OP_ILLEGAL)) {
                throw NfsVersionUnsupportedException("NFS server does not support NFSv4.$minorVersion sessions")
            }
            throw nfsStatusException(result.status, reachingExport = false)
        }
        return result
    }

    private fun rpc(
        args: COMPOUND4args,
        timeoutMs: Long,
    ): COMPOUND4res {
        val result = COMPOUND4res()
        try {
            connection.call(nfs4_prot.NFS4_PROGRAM, nfs4_prot.NFS_V4, nfs4_prot.NFSPROC4_COMPOUND_4, auth, args, result, timeoutMs)
        } catch (error: BadXdrOncRpcException) {
            // NFSv3-only servers such as rclone accept the call but answer with a bare v3 status.
            if (answered) throw error
            throw NfsVersionUnsupportedException("NFS server does not speak NFSv4").apply { initCause(error) }
        }
        answered = true
        return result
    }

    companion object {
        private const val IMPLEMENTATION_DOMAIN = "neerdael.nl"
        private const val IMPLEMENTATION_NAME = "Milkbeat"
        private const val MAX_READ_BYTES = 64 * 1024
        private const val LIST_REPLY_BYTES = 32 * 1024
        private const val DIRECTORY_COUNT = 8 * 1024
        private const val COMPOUND_OVERHEAD_BYTES = 1024
        private const val REQUEST_BYTES = 16 * 1024
        private const val CACHED_REPLY_BYTES = 1024
        private const val MAX_OPERATIONS = 16

        // SEQUENCE, PUTROOTFH or PUTFH, GETFH and GETATTR around the LOOKUPs of one compound.
        private const val LOOKUP_FIXED_OPERATIONS = 4
        private const val CLOSE_TIMEOUT_MS = 2_000L
        private val ATTRIBUTES = intArrayOf(nfs4_prot.FATTR4_TYPE, nfs4_prot.FATTR4_SIZE, nfs4_prot.FATTR4_TIME_MODIFY)

        fun open(
            source: MusicFolder,
            minorVersion: Int,
        ): Nfs4Session {
            val auth = NfsAuthSys(source.uid, source.gid)
            val session = Nfs4Session(NfsRpcConnection.connect(source.host, source.port), auth, minorVersion)
            try {
                if (minorVersion > 0) session.establishSession()
                return session
            } catch (error: Throwable) {
                session.close()
                throw error
            }
        }

        private fun COMPOUND4res.op(code: Int): nfs_resop4 = resarray.first { it.resop == code }

        /**
         * AttributeMap in nfs4j-core cannot decode FATTR4_TIME_MODIFY, so the reply's attribute list is decoded
         * here with the generated attribute types, in the ascending bit order the protocol defines.
         */
        private fun attributes(attributes: fattr4): NfsAttributes {
            var type = NfsFileType.OTHER
            var size = 0L
            var modified = 0L
            Xdr(attributes.attr_vals.value).use { xdr ->
                xdr.beginDecoding()
                for (attribute in attributes.attrmask) {
                    when (attribute) {
                        nfs4_prot.FATTR4_TYPE -> {
                            type =
                                when (fattr4_type(xdr).value) {
                                    nfs_ftype4.NF4DIR -> NfsFileType.DIRECTORY
                                    nfs_ftype4.NF4REG -> NfsFileType.REGULAR
                                    else -> NfsFileType.OTHER
                                }
                        }

                        nfs4_prot.FATTR4_SIZE -> {
                            size = fattr4_size(xdr).value
                        }

                        nfs4_prot.FATTR4_TIME_MODIFY -> {
                            val time = fattr4_time_modify(xdr)
                            modified = nfsTimeMillis(time.seconds, time.nseconds.toLong())
                        }

                        else -> {
                            throw IOException("NFS server returned unrequested attribute $attribute")
                        }
                    }
                }
                xdr.endDecoding()
            }
            return NfsAttributes(type, size, modified)
        }
    }
}
