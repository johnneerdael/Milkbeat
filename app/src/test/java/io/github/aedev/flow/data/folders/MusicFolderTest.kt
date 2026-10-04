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
}
