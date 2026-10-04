package io.github.aedev.flow.data.folders

import org.dcache.nfs.ChimeraNFSException
import org.dcache.nfs.nfsstat
import org.dcache.nfs.v4.xdr.nfs_opnum4
import org.dcache.oncrpc4j.rpc.OncRpcAcceptedException
import org.dcache.oncrpc4j.rpc.OncRpcRejectedException
import org.dcache.oncrpc4j.rpc.RpcAccepsStatus
import org.dcache.oncrpc4j.rpc.RpcRejectStatus
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The export refused this client in the way servers refuse requests from unprivileged source ports. Android
 * apps cannot bind ports below 1024, so the export needs the `insecure` option (Synology: "Allow connections
 * from non-privileged ports").
 */
class NfsInsecurePortRequiredException(
    message: String,
) : IOException(message)

/** The server does not speak the attempted NFS version or minor version. */
internal class NfsVersionUnsupportedException(
    message: String,
) : IOException(message)

internal enum class NfsDialect { V3, V4_0, V4_1 }

/** For AUTO, [learned] (the dialect this server last accepted) is tried first so each operation costs one connection. */
internal fun nfsDialects(
    version: NfsVersion,
    learned: NfsDialect? = null,
): List<NfsDialect> =
    when (version) {
        NfsVersion.AUTO -> listOf(NfsDialect.V4_1, NfsDialect.V4_0, NfsDialect.V3).sortedByDescending { it == learned }
        NfsVersion.V3 -> listOf(NfsDialect.V3)
        NfsVersion.V4 -> listOf(NfsDialect.V4_0)
        NfsVersion.V4_1 -> listOf(NfsDialect.V4_1)
    }

/**
 * Opens the first dialect the server speaks. Only [NfsVersionUnsupportedException] moves on to an older
 * dialect; authentication, permission and network failures are reported as they are so they are not masked.
 */
internal inline fun <T> firstSupportedDialect(
    dialects: List<NfsDialect>,
    open: (NfsDialect) -> T,
): T {
    var unsupported: NfsVersionUnsupportedException? = null
    for (dialect in dialects) {
        try {
            return open(dialect)
        } catch (error: NfsVersionUnsupportedException) {
            unsupported?.let(error::addSuppressed)
            unsupported = error
        }
    }
    throw checkNotNull(unsupported)
}

/** Path components below the export, for LOOKUP. */
internal fun nfsComponents(
    source: MusicFolder,
    path: String,
): List<String> = source.remotePath(path).split('/').filter(String::isNotEmpty)

/** Path components from the NFSv4 server root: the export followed by [nfsComponents]. */
internal fun nfs4Components(
    source: MusicFolder,
    path: String,
): List<String> = source.nfsExport().split('/').filter(String::isNotEmpty) + nfsComponents(source, path)

/** Symlinks and special files are skipped: resolving a link target could leave the configured root. */
internal fun nfsMusicEntry(
    parent: String,
    entry: NfsDirEntry,
    includePlaylists: Boolean = false,
): MusicFolderEntry? {
    val name = entry.name
    if (name == "." || name == ".." || name.isEmpty() || name.any { it in "/\\\u0000" }) return null
    val folder =
        when (entry.attributes.type) {
            NfsFileType.DIRECTORY -> true
            NfsFileType.REGULAR -> false
            NfsFileType.OTHER -> return null
        }
    if (!folder && !isListedFile(name, null, includePlaylists)) return null
    return MusicFolderEntry(
        name,
        childLocation(parent, name, MusicFolderKind.NFS.allowsColon),
        folder,
        entry.attributes.size,
        entry.attributes.modified,
    )
}

internal fun nfsTimeMillis(
    seconds: Long,
    nanoseconds: Long,
): Long = seconds * 1_000 + nanoseconds / 1_000_000

/**
 * Statuses that only describe the server's view of this connection or handle; a fresh connection and lookup
 * can succeed where the old one failed, e.g. after the server dropped an idle TCP connection or expired an
 * NFSv4.1 lease while playback was paused.
 */
internal fun isNfsReconnectable(error: Throwable): Boolean =
    when (error) {
        is SocketTimeoutException, is NfsInsecurePortRequiredException, is NfsVersionUnsupportedException -> false
        is ChimeraNFSException -> error.status in RECONNECTABLE_STATUSES
        is IOException -> true
        else -> false
    }

private val RECONNECTABLE_STATUSES =
    setOf(
        nfsstat.NFSERR_STALE,
        nfsstat.NFSERR_FHEXPIRED,
        nfsstat.NFSERR_BADSESSION,
        nfsstat.NFSERR_DEADSESSION,
        nfsstat.NFSERR_STALE_CLIENTID,
        nfsstat.NFSERR_EXPIRED,
        nfsstat.NFSERR_SEQ_MISORDERED,
    )

/**
 * Maps a failed NFS status to an exception. A permission status while the export itself is being reached
 * (MNT, or the NFSv4 LOOKUP chain to the export) is how Linux and most NAS servers reject an unprivileged
 * source port, so it becomes [NfsInsecurePortRequiredException].
 */
internal fun nfsStatusException(
    status: Int,
    reachingExport: Boolean,
): IOException {
    if (reachingExport && status == nfsstat.NFSERR_PERM) {
        return NfsInsecurePortRequiredException("NFS export refused the connection (${nfsstat.toString(status)})")
    }
    if (status == nfsstat.NFSERR_MINOR_VERS_MISMATCH) return NfsVersionUnsupportedException(nfsstat.toString(status))
    return try {
        nfsstat.throwIfNeeded(status)
        IOException("NFS request failed")
    } catch (error: ChimeraNFSException) {
        error
    }
}

/**
 * Maps an RPC-level rejection. oncrpc4j exposes the reject and accept status only through the exception
 * message, so it is compared against the library's own status names. An AUTH_ERROR for AUTH_SYS means
 * AUTH_TOOWEAK in practice: the export wants a privileged port (or Kerberos, which this client cannot offer).
 */
internal fun rpcFailure(error: IOException): IOException =
    when {
        error is OncRpcRejectedException && error.message == RpcRejectStatus.toString(RpcRejectStatus.AUTH_ERROR) -> {
            NfsInsecurePortRequiredException("NFS server rejected the AUTH_SYS credentials")
        }

        error is OncRpcAcceptedException &&
            error.message in setOf(RpcAccepsStatus.PROG_UNAVAIL, RpcAccepsStatus.PROG_MISMATCH).map(RpcAccepsStatus::toString) -> {
            NfsVersionUnsupportedException("NFS server does not offer this protocol version (${error.message})")
        }

        else -> {
            error
        }
    }

/**
 * NFS3ERR_JUKEBOX / NFS4ERR_DELAY / NFS4ERR_GRACE ask the client to retry shortly; a rebooted server answers
 * GRACE for its whole grace period, so retries continue for up to [NFS_TIMEOUT_MS].
 */
internal inline fun retryWhileDelayed(
    status: () -> Int,
    attempt: () -> Unit,
) {
    val deadline = System.nanoTime() + NFS_TIMEOUT_MS * 1_000_000
    while (true) {
        attempt()
        val current = status()
        if ((current != nfsstat.NFSERR_DELAY && current != nfsstat.NFSERR_GRACE) || System.nanoTime() > deadline) return
        Thread.sleep(NFS_DELAY_RETRY_MS)
    }
}

private const val NFS_DELAY_RETRY_MS = 1_000L

/**
 * Whether a failed PUTROOTFH/PUTFH + LOOKUP compound was refused while still reaching the export. [resops] are the
 * reply's operations up to and including the failing one, the first LOOKUP resolves component [firstComponent], and
 * the export spans the first [exportDepth] components. A refusal below the export is an ordinary permission error.
 */
internal fun nfs4RefusedBeforeExport(
    resops: List<Int>,
    firstComponent: Int,
    exportDepth: Int,
): Boolean =
    when (resops.lastOrNull()) {
        nfs_opnum4.OP_PUTROOTFH -> true
        nfs_opnum4.OP_LOOKUP -> firstComponent + resops.count { it == nfs_opnum4.OP_LOOKUP } - 1 < exportDepth
        else -> false
    }
