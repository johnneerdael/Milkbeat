package io.github.aedev.flow.ui.screens.folders

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.MusicFolderEntry
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.data.folders.MusicFolderRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import java.io.IOException

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(
    manifest = org.robolectric.annotation.Config.NONE,
    sdk = [28],
    application = android.app.Application::class,
)
class MusicFoldersViewModelTest {
    @After fun cleanup() {
        Dispatchers.resetMain()
    }

    @Test fun changingSourcesCancelsOldListingAndBackReturnsToSources() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = mockk<MusicFolderRepository>()
            val first = MusicFolder(name = "First", kind = MusicFolderKind.SMB, host = "nas", share = "First")
            val second = first.copy(id = "second", name = "Second")
            coEvery { repository.folders } returns flowOf(listOf(first, second))
            val pending = CompletableDeferred<List<MusicFolderEntry>>()
            coEvery { repository.list(first, "") } coAnswers { pending.await() }
            coEvery { repository.list(second, "") } returns listOf(MusicFolderEntry("track.mp3", "track.mp3", false))
            val vm = MusicFoldersViewModel(repository, mockk(relaxed = true))
            vm.openSource(first)
            advanceUntilIdle()
            vm.openSource(second)
            advanceUntilIdle()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.browser.first { it.source == second && !it.loading } }
            }
            pending.complete(listOf(MusicFolderEntry("wrong.mp3", "wrong.mp3", false)))
            advanceUntilIdle()
            assertThat(vm.browser.value.source).isEqualTo(second)
            assertThat(
                vm.browser.value.entries
                    .single()
                    .name,
            ).isEqualTo("track.mp3")
            vm.back()
            assertThat(vm.browser.value.source).isNull()
        }

    @Test fun failedAccessIsVisibleAndRetryLoadsDirectory() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = mockk<MusicFolderRepository>()
            val source = MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music")
            coEvery { repository.folders } returns flowOf(listOf(source))
            coEvery { repository.list(source, "") } throws IOException()
            val vm = MusicFoldersViewModel(repository, mockk(relaxed = true))
            vm.openSource(source)
            advanceUntilIdle()
            assertThat(vm.browser.value.failed).isTrue()
            assertThat(vm.browser.value.loading).isFalse()
            coEvery { repository.list(source, "") } returns emptyList()
            vm.refresh()
            advanceUntilIdle()
            withContext(Dispatchers.Default) {
                withTimeout(5_000) { vm.browser.first { !it.loading } }
            }
            assertThat(vm.browser.value.failed).isFalse()
        }

    @Test fun editingPasswordInvalidatesSuccessfulAccessTest() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = mockk<MusicFolderRepository>()
            coEvery { repository.folders } returns flowOf(emptyList())
            coEvery { repository.test(any(), any(), any()) } answers { firstArg() }
            val vm = MusicFoldersViewModel(repository, mockk(relaxed = true))
            vm.edit(MusicFolder(name = "NAS", kind = MusicFolderKind.SMB, host = "nas", share = "Music"))
            vm.testAccess()
            advanceUntilIdle()
            assertThat(vm.editor.value?.access).isEqualTo(FolderAccess.SUCCESS)
            vm.updateDraft { copy(password = "changed") }
            assertThat(vm.editor.value?.access).isEqualTo(FolderAccess.IDLE)
        }

    @Test fun newNetworkFoldersStartWithProtocolDefaultsAndEmptySecrets() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = mockk<MusicFolderRepository>()
            coEvery { repository.folders } returns flowOf(emptyList())
            val vm = MusicFoldersViewModel(repository, mockk(relaxed = true))
            for ((kind, port) in listOf(MusicFolderKind.SMB to "445", MusicFolderKind.SFTP to "22", MusicFolderKind.NFS to "2049")) {
                vm.create(kind)
                val draft = vm.editor.value!!
                assertThat(draft.source.kind).isEqualTo(kind)
                assertThat(draft.port).isEqualTo(port)
                assertThat(draft.password).isEmpty()
                assertThat(draft.privateKey).isEmpty()
            }
        }

    @Test fun successfulSftpTestPinsTheServerKeyInTheDraft() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = mockk<MusicFolderRepository>()
            coEvery { repository.folders } returns flowOf(emptyList())
            coEvery { repository.test(any(), any(), any()) } answers { firstArg<MusicFolder>().copy(hostKey = "ssh-ed25519 SHA256:abc") }
            val vm = MusicFoldersViewModel(repository, mockk(relaxed = true))
            vm.create(MusicFolderKind.SFTP)
            vm.updateDraft { copy(source = source.copy(name = "Box", host = "box", username = "me"), password = "pw") }
            vm.testAccess()
            advanceUntilIdle()
            assertThat(vm.editor.value?.access).isEqualTo(FolderAccess.SUCCESS)
            assertThat(
                vm.editor.value
                    ?.source
                    ?.hostKey,
            ).isEqualTo("ssh-ed25519 SHA256:abc")
        }

    @Test fun invalidWebDavUrlIsRejectedBeforeAnyNetworkAccess() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = mockk<MusicFolderRepository>()
            coEvery { repository.folders } returns flowOf(emptyList())
            val vm = MusicFoldersViewModel(repository, mockk(relaxed = true))
            vm.create(MusicFolderKind.WEBDAV)
            vm.updateDraft { copy(source = source.copy(name = "Cloud", url = "https://user:pw@cloud.test/dav")) }
            vm.testAccess()
            advanceUntilIdle()
            assertThat(vm.editor.value?.error).isEqualTo(io.github.aedev.flow.R.string.music_folders_invalid_webdav)
        }

    @Test fun privateKeySignInNeedsAKeyUnlessOneIsAlreadySaved() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val repository = mockk<MusicFolderRepository>()
            coEvery { repository.folders } returns flowOf(emptyList())
            coEvery { repository.test(any(), any(), any()) } answers { firstArg() }
            val vm = MusicFoldersViewModel(repository, mockk(relaxed = true))
            val box =
                MusicFolder(name = "Box", kind = MusicFolderKind.SFTP, host = "box", username = "me", hostKey = "ssh-ed25519 SHA256:abc")
            vm.edit(box)
            vm.updateDraft { copy(source = source.copy(keyAuth = true)) }
            vm.testAccess()
            advanceUntilIdle()
            assertThat(vm.editor.value?.error).isEqualTo(io.github.aedev.flow.R.string.music_folders_private_key_required)
            vm.edit(box.copy(keyAuth = true))
            vm.testAccess()
            advanceUntilIdle()
            assertThat(vm.editor.value?.access).isEqualTo(FolderAccess.SUCCESS)
        }
}
