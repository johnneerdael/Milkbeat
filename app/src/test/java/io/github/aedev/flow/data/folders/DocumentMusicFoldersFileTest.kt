package io.github.aedev.flow.data.folders

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DocumentMusicFoldersFileTest {
    @get:Rule val temporary = TemporaryFolder()

    private val documents = DocumentMusicFolders(ApplicationProvider.getApplicationContext())

    @Test fun directlySelectedFolderListsSongsAndDirectories() {
        val root = temporary.newFolder("Music")
        File(root, "Albums").mkdir()
        File(root, "Song.flac").writeText("audio")
        File(root, "Cover.jpg").writeText("image")
        File(root, "Mix.m3u").writeText("Song.flac")
        val source = documents.add(Uri.fromFile(root))

        assertThat(source.isValid()).isTrue()
        assertThat(source.name).isEqualTo("Music")
        assertThat(documents.list(source, "").map { it.name }).containsExactly("Albums", "Song.flac")
        assertThat(documents.list(source, "", includePlaylists = true).map { it.name })
            .containsExactly("Albums", "Song.flac", "Mix.m3u")
    }

    @Test fun directoryListingCannotEscapeSelectedFolder() {
        val root = temporary.newFolder("Selected")
        val outside = temporary.newFolder("SelectedElsewhere")
        val source = MusicFolder(name = "Selected", kind = MusicFolderKind.LOCAL, treeUri = Uri.fromFile(root).toString())

        assertThrows(IllegalArgumentException::class.java) { documents.list(source, Uri.fromFile(outside).toString()) }
    }

    @Test fun removedFolderFailsInsteadOfLookingEmpty() {
        val root = temporary.newFolder("Disconnected")
        val source = documents.add(Uri.fromFile(root))
        root.delete()

        assertThrows(java.io.FileNotFoundException::class.java) { documents.list(source, "") }
    }

    @Test fun symlinkOutsideSelectedFolderIsNotListed() {
        val root = temporary.newFolder("Music")
        val outside = temporary.newFolder("Private")
        java.nio.file.Files
            .createSymbolicLink(File(root, "Escape").toPath(), outside.toPath())
        val source = documents.add(Uri.fromFile(root))

        assertThat(documents.list(source, "")).isEmpty()
    }

    @Test fun directFileUriDoesNotAcceptAnAuthorityOrRelativePath() {
        for (uri in listOf("file://server/Music", "file:Music", "file:///Music?redirect=other", "file:///Music#other")) {
            val source = MusicFolder(name = "Invalid", kind = MusicFolderKind.LOCAL, treeUri = uri)
            assertThat(source.isValid()).isFalse()
        }
    }

    @Test fun unreadableDescendantDoesNotHideReadableMusic() {
        val root = temporary.newFolder("Music")
        val hidden = File(root, "Unavailable").apply { mkdir() }
        File(root, "Song.flac").writeText("audio")
        val source = documents.add(Uri.fromFile(root))
        hidden.setReadable(false, false)
        try {
            assertThat(documents.list(source, "").map { it.name }).containsExactly("Song.flac")
        } finally {
            hidden.setReadable(true, true)
        }
    }

    @Test fun aliasesCannotCreateDuplicateDirectoryKeys() {
        val root = temporary.newFolder("Music")
        val albums = File(root, "Albums").apply { mkdir() }
        java.nio.file.Files
            .createSymbolicLink(File(root, "Shortcut").toPath(), albums.toPath())
        val source = documents.add(Uri.fromFile(root))

        val entries = documents.list(source, "")
        assertThat(entries.map { it.location }).containsNoDuplicates()
        assertThat(entries).hasSize(1)
    }

    @Test fun directoryLinksCannotLeadBackToAnAncestor() {
        val root = temporary.newFolder("Music")
        val albums = File(root, "Albums").apply { mkdir() }
        java.nio.file.Files
            .createSymbolicLink(File(albums, "Loop").toPath(), root.toPath())
        val source = documents.add(Uri.fromFile(root))

        assertThat(documents.list(source, Uri.fromFile(albums).toString())).isEmpty()
    }
}
