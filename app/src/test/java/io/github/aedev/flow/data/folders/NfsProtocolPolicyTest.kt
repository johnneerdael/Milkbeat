package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import org.dcache.nfs.nfsstat
import org.dcache.nfs.status.BadSessionException
import org.dcache.nfs.status.NoEntException
import org.dcache.nfs.status.PermException
import org.dcache.nfs.status.StaleException
import org.dcache.oncrpc4j.rpc.OncRpcAcceptedException
import org.dcache.oncrpc4j.rpc.OncRpcRejectedException
import org.dcache.oncrpc4j.rpc.RpcAccepsStatus
import org.dcache.oncrpc4j.rpc.RpcRejectStatus
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException

class NfsProtocolPolicyTest {
    private fun folder(
        share: String,
        root: String = "",
        version: NfsVersion = NfsVersion.AUTO,
    ) = MusicFolder(name = "NAS", kind = MusicFolderKind.NFS, host = "nas", share = share, root = root, nfsVersion = version)

    @Test fun autoTriesNewestDialectFirstAndExplicitVersionsTryOnlyThemselves() {
        assertThat(nfsDialects(NfsVersion.AUTO)).containsExactly(NfsDialect.V4_1, NfsDialect.V4_0, NfsDialect.V3).inOrder()
        assertThat(nfsDialects(NfsVersion.V3)).containsExactly(NfsDialect.V3)
        assertThat(nfsDialects(NfsVersion.V4)).containsExactly(NfsDialect.V4_0)
        assertThat(nfsDialects(NfsVersion.V4_1)).containsExactly(NfsDialect.V4_1)
        assertThat(nfsDialects(NfsVersion.AUTO, learned = NfsDialect.V3))
            .containsExactly(NfsDialect.V3, NfsDialect.V4_1, NfsDialect.V4_0)
            .inOrder()
        assertThat(nfsDialects(NfsVersion.V4, learned = NfsDialect.V3)).containsExactly(NfsDialect.V4_0)
    }

    @Test fun pathsMapToLookupComponentsBelowTheExportOrTheV4Root() {
        val source = folder("/volume1/music/", root = "Albums")
        assertThat(nfsComponents(source, "")).containsExactly("Albums")
        assertThat(nfsComponents(source, "Café del Mar/Ünïcødé – Track.ogg"))
            .containsExactly("Albums", "Café del Mar", "Ünïcødé – Track.ogg")
            .inOrder()
        assertThat(nfs4Components(source, "Abbey Road")).containsExactly("volume1", "music", "Albums", "Abbey Road").inOrder()
        assertThat(nfs4Components(folder("/"), "")).isEmpty()
        assertThat(nfs4Components(folder("", root = "music"), "a")).containsExactly("music", "a").inOrder()
    }

    @Test fun pathsCannotEscapeTheConfiguredRoot() {
        val source = folder("/export", root = "music")
        assertThrows(IllegalArgumentException::class.java) { nfsComponents(source, "../secret") }
        assertThrows(IllegalArgumentException::class.java) { nfs4Components(source, "a/./b") }
        assertThat(folder("/export/../etc").isValid()).isFalse()
    }

    @Test fun entriesKeepDirectoriesAndMusicAndSkipEverythingElse() {
        val dir = NfsAttributes(NfsFileType.DIRECTORY, 4096, 1_000)
        val file = NfsAttributes(NfsFileType.REGULAR, 1234, 1_700_000_000_123)
        val link = NfsAttributes(NfsFileType.OTHER, 20, 0)
        assertThat(nfsMusicEntry("Albums", NfsDirEntry("Abbey Road", dir)))
            .isEqualTo(MusicFolderEntry("Abbey Road", "Albums/Abbey Road", true, 4096, 1_000))
        assertThat(nfsMusicEntry("", NfsDirEntry("Été.flac", file)))
            .isEqualTo(MusicFolderEntry("Été.flac", "Été.flac", false, 1234, 1_700_000_000_123))
        assertThat(nfsMusicEntry("", NfsDirEntry("notes.txt", file))).isNull()
        assertThat(nfsMusicEntry("", NfsDirEntry("Mix.m3u8", file))).isNull()
        assertThat(nfsMusicEntry("", NfsDirEntry("Mix.m3u8", file), includePlaylists = true)?.location).isEqualTo("Mix.m3u8")
        assertThat(nfsMusicEntry("", NfsDirEntry("link.mp3", link))).isNull()
        assertThat(nfsMusicEntry("", NfsDirEntry(".", dir))).isNull()
        assertThat(nfsMusicEntry("", NfsDirEntry("..", dir))).isNull()
        assertThat(nfsMusicEntry("", NfsDirEntry("a\\b.mp3", file))).isNull()
        assertThat(nfsMusicEntry("Live", NfsDirEntry("Act I: Overture.flac", file)))
            .isEqualTo(MusicFolderEntry("Act I: Overture.flac", "Live/Act I: Overture.flac", false, 1234, 1_700_000_000_123))
    }

    @Test fun nfsTimesBecomeEpochMillis() {
        assertThat(nfsTimeMillis(1_700_000_000, 999_999_999)).isEqualTo(1_700_000_000_999)
        assertThat(nfsTimeMillis(0xFFFF_FFFFL, 0)).isEqualTo(4_294_967_295_000)
    }

    @Test fun permissionRefusalWhileReachingTheExportMeansAPrivilegedPortIsRequired() {
        assertThat(
            nfsStatusException(nfsstat.NFSERR_PERM, reachingExport = true),
        ).isInstanceOf(NfsInsecurePortRequiredException::class.java)
        assertThat(nfsStatusException(nfsstat.NFSERR_PERM, reachingExport = false)).isInstanceOf(PermException::class.java)
        assertThat(nfsStatusException(nfsstat.NFSERR_NOENT, reachingExport = true)).isInstanceOf(NoEntException::class.java)
        assertThat(nfsStatusException(nfsstat.NFSERR_MINOR_VERS_MISMATCH, reachingExport = true))
            .isInstanceOf(NfsVersionUnsupportedException::class.java)
    }

    @Test fun rpcRejectionsMapToPortHintOrVersionFallback() {
        assertThat(
            rpcFailure(OncRpcRejectedException(RpcRejectStatus.AUTH_ERROR)),
        ).isInstanceOf(NfsInsecurePortRequiredException::class.java)
        assertThat(
            rpcFailure(OncRpcAcceptedException(RpcAccepsStatus.PROG_MISMATCH)),
        ).isInstanceOf(NfsVersionUnsupportedException::class.java)
        assertThat(
            rpcFailure(OncRpcAcceptedException(RpcAccepsStatus.PROG_UNAVAIL)),
        ).isInstanceOf(NfsVersionUnsupportedException::class.java)
        val unrelated = OncRpcAcceptedException(RpcAccepsStatus.GARBAGE_ARGS)
        assertThat(rpcFailure(unrelated)).isSameInstanceAs(unrelated)
        val mismatch = OncRpcRejectedException(RpcRejectStatus.RPC_MISMATCH)
        assertThat(rpcFailure(mismatch)).isSameInstanceAs(mismatch)
    }

    @Test fun onlyVersionMismatchFallsBackToAnOlderDialect() {
        val tried = mutableListOf<NfsDialect>()
        val opened =
            firstSupportedDialect(nfsDialects(NfsVersion.AUTO)) {
                tried += it
                if (it == NfsDialect.V3) "v3" else throw NfsVersionUnsupportedException(it.name)
            }
        assertThat(opened).isEqualTo("v3")
        assertThat(tried).containsExactly(NfsDialect.V4_1, NfsDialect.V4_0, NfsDialect.V3).inOrder()

        tried.clear()
        assertThrows(NfsInsecurePortRequiredException::class.java) {
            firstSupportedDialect(nfsDialects(NfsVersion.AUTO)) {
                tried += it
                throw NfsInsecurePortRequiredException("refused")
            }
        }
        assertThat(tried).containsExactly(NfsDialect.V4_1)

        val error =
            assertThrows(NfsVersionUnsupportedException::class.java) {
                firstSupportedDialect(nfsDialects(NfsVersion.AUTO)) { throw NfsVersionUnsupportedException(it.name) }
            }
        assertThat(error.message).isEqualTo("V3")
        assertThat(error.suppressed.map { it.message }).containsExactly("V4_0")
    }

    @Test fun onlyConnectionAndHandleFailuresAreRetriedOnAFreshConnection() {
        assertThat(isNfsReconnectable(EOFException("Disconnected"))).isTrue()
        assertThat(isNfsReconnectable(IOException("Broken pipe"))).isTrue()
        assertThat(isNfsReconnectable(StaleException())).isTrue()
        assertThat(isNfsReconnectable(BadSessionException())).isTrue()
        assertThat(isNfsReconnectable(SocketTimeoutException())).isFalse()
        assertThat(isNfsReconnectable(NoEntException())).isFalse()
        assertThat(isNfsReconnectable(NfsInsecurePortRequiredException("refused"))).isFalse()
        assertThat(isNfsReconnectable(IllegalStateException())).isFalse()
    }
}
