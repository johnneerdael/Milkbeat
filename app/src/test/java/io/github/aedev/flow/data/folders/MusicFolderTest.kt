package io.github.aedev.flow.data.folders

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MusicFolderTest {
    @Test fun uriBackedIdsSurviveUnicodeAndQueueSerialization() {
        val uri = Uri.parse("content://documents/tree/root/document/album%2FÉté%20%231.flac")
        val id = LocalMediaIds.of(uri)
        assertThat(LocalMediaIds.isLocal(id)).isTrue()
        assertThat(LocalMediaIds.audioUri(id)).isEqualTo(uri)
        assertThat(LocalMediaIds.mediaStoreId(id)).isNull()
    }

    @Test fun legacyIdsStillResolveAudioAndVideo() {
        val id = LocalMediaIds.of(42L)
        assertThat(LocalMediaIds.audioUri(id).toString()).isEqualTo("content://media/external/audio/media/42")
        assertThat(LocalMediaIds.videoUri(id).toString()).isEqualTo("content://media/external/video/media/42")
    }

    @Test fun unsafeSchemesCannotBecomeLocalTracks() {
        assertThrows(IllegalArgumentException::class.java) { LocalMediaIds.of(Uri.parse("https://example.com/a.mp3")) }
        assertThat(LocalMediaIds.audioUri("local_uri_invalid")).isNull()
    }

    @Test fun smbUriContainsIdentityAndRevisionButNoCredentials() {
        val source =
            MusicFolder(
                id = "source",
                revision = "revision",
                name = "NAS",
                kind = MusicFolderKind.SMB,
                host = "nas",
                share = "Music",
                root = "Albums",
            )
        val uri = source.remoteUri("Été/A #1.flac")
        assertThat(uri.host).isEqualTo("source")
        assertThat(uri.getQueryParameter("revision")).isEqualTo("revision")
        assertThat(uri.path).isEqualTo("/Été/A #1.flac")
        assertThat(source.smbPath(uri.path.orEmpty())).isEqualTo("Albums\\Été\\A #1.flac")
    }

    @Test fun smbPathsCannotEscapeConfiguredRoot() {
        val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music")
        for (path in listOf("../private", "foo/../private", "foo\\..\\private", "//other/share", "file:stream", "foo/./bar")) {
            assertThrows(IllegalArgumentException::class.java) { source.smbPath(path) }
        }
    }

    @Test fun smbConfigurationValidatesHostSharePortAndName() {
        val valid = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas.local", share = "Music")
        assertThat(valid.isValid()).isTrue()
        for (invalid in listOf(
            valid.copy(host = "smb://nas"),
            valid.copy(share = "Music/private"),
            valid.copy(port = 0),
            valid.copy(name = ""),
            valid.copy(root = "../private"),
        )) {
            assertThat(invalid.isValid()).isFalse()
        }
    }

    @Test fun folderTracksNeverRequestYoutubeThumbnailFallbacks() {
        val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music")
        val track = MusicFolderEntry("Song.flac", "private/album/Song.flac", false).track(source)
        assertThat(track.listThumbnailUrl).doesNotContain("ytimg")
        assertThat(track.highResThumbnailUrl).doesNotContain("ytimg")
    }

    @Test fun everyRemoteKindGetsItsOwnSchemeAndBecomesALocalTrack() {
        val sources =
            listOf(
                MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music"),
                MusicFolder(name = "Cloud", kind = MusicFolderKind.WEBDAV, url = "https://cloud.test/dav/Music"),
                MusicFolder(name = "Box", kind = MusicFolderKind.SFTP, host = "box", username = "me"),
                MusicFolder(name = "Export", kind = MusicFolderKind.NFS, host = "nas", share = "/volume1/music"),
            )
        assertThat(sources.map { it.remoteUri("a.flac").scheme }.toSet()).isEqualTo(MusicFolderKind.remoteSchemes)
        for (source in sources) {
            assertThat(source.isValid()).isTrue()
            val uri = source.remoteUri("Été/A #1.flac")
            assertThat(MusicFolderKind.forScheme(uri.scheme)).isEqualTo(source.kind)
            assertThat(LocalMediaIds.audioUri(LocalMediaIds.of(uri))).isEqualTo(uri)
        }
    }

    @Test fun sftpRootsAreAbsoluteOnlyWithALeadingSlashAndNfsPathsStayBelowTheExport() {
        val sftp = MusicFolder(name = "Box", kind = MusicFolderKind.SFTP, host = "box")
        assertThat(sftp.copy(root = "/srv/music").remotePath("Album/a.flac")).isEqualTo("/srv/music/Album/a.flac")
        assertThat(sftp.copy(root = "music").remotePath("a.flac")).isEqualTo("music/a.flac")
        assertThat(sftp.remotePath("")).isEmpty()
        assertThat(sftp.copy(root = "/").remotePath("")).isEqualTo("/")
        val nfs = MusicFolder(name = "Export", kind = MusicFolderKind.NFS, host = "nas", share = "/volume1/music/", root = "/Albums")
        assertThat(nfs.nfsExport()).isEqualTo("/volume1/music")
        assertThat(nfs.remotePath("a.flac")).isEqualTo("Albums/a.flac")
        assertThat(nfs.copy(share = "").nfsExport()).isEqualTo("/")
        for (path in listOf("../etc", "a/../../etc", "//host/x")) {
            assertThrows(IllegalArgumentException::class.java) { sftp.remotePath(path) }
            assertThrows(IllegalArgumentException::class.java) { nfs.remotePath(path) }
        }
    }

    @Test fun webDavUrlsMustBePlainHttpCollections() {
        val source = MusicFolder(name = "Cloud", kind = MusicFolderKind.WEBDAV, url = " https://cloud.test/remote.php/dav/files/me ")
        assertThat(source.webDavUrl().toString()).isEqualTo("https://cloud.test/remote.php/dav/files/me/")
        assertThat(source.copy(url = "http://nas:8080/").webDavUrl().toString()).isEqualTo("http://nas:8080/")
        for (invalid in listOf("ftp://nas/", "https://me:pw@nas/", "https://nas/?x=1", "https://nas/#a", "nas/dav", "")) {
            assertThat(source.copy(url = invalid).isValid()).isFalse()
        }
    }

    @Test fun networkConfigurationsRejectBadHostsPortsAndIds() {
        val sftp = MusicFolder(name = "Box", kind = MusicFolderKind.SFTP, host = "box")
        val nfs = MusicFolder(name = "Export", kind = MusicFolderKind.NFS, host = "nas", share = "/music")
        for (invalid in listOf(sftp.copy(host = "sftp://box"), sftp.copy(port = 0), sftp.copy(root = "../x"), sftp.copy(name = " "))) {
            assertThat(invalid.isValid()).isFalse()
        }
        for (invalid in listOf(nfs.copy(uid = -1), nfs.copy(gid = -1), nfs.copy(share = "/a/../b"), nfs.copy(host = ""))) {
            assertThat(invalid.isValid()).isFalse()
        }
    }

    @Test fun foldersSavedBeforeNewKindsStillDecode() {
        val json = """[{"id":"a","revision":"r","name":"NAS","kind":"SMB","host":"nas","share":"Music"}]"""
        val decoded =
            kotlinx.serialization.json.Json
                .decodeFromString<List<MusicFolder>>(json)
                .single()
        assertThat(decoded.port).isEqualTo(445)
        assertThat(decoded.isValid()).isTrue()
        assertThat(MusicFolder(name = "Box", kind = MusicFolderKind.SFTP).port).isEqualTo(22)
        assertThat(MusicFolder(name = "Export", kind = MusicFolderKind.NFS).port).isEqualTo(2049)
    }
}
