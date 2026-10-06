package io.github.aedev.flow.ui.screens.music

import android.app.Application
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.DownloadManager
import io.github.aedev.flow.data.music.model.MusicQueueOrigin
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalMusicPlaybackRequestTest {
    private val dispatcher = StandardTestDispatcher()
    private val stores = mutableListOf<ViewModelStore>()
    private val track = MusicTrack("local_42", "Local song", "Artist", "", 200)
    private val other = track.copy(videoId = "local_43", title = "Other song")
    private val requests = mutableListOf<Request>()
    private val player = mockk<Player>(relaxed = true)
    private val savedFields = mutableListOf<Pair<java.lang.reflect.Field, Any?>>()

    private data class Request(
        val seed: String?,
        val collection: String?,
        val queue: List<String>,
        val uri: Uri?,
    )

    @Before fun setup() {
        Dispatchers.setMain(dispatcher)
        for ((name, value) in listOf("isInitialized" to true, "player" to player)) {
            val field = EnhancedMusicPlayerManager::class.java.getDeclaredField(name).apply { isAccessible = true }
            savedFields.add(field to field.get(EnhancedMusicPlayerManager))
            field.set(EnhancedMusicPlayerManager, value)
        }
        every { player.setMediaItems(any<List<MediaItem>>(), any(), any()) } answers {
            requests.add(
                Request(
                    EnhancedMusicPlayerManager.pendingRadioSeedId,
                    EnhancedMusicPlayerManager.pendingRadioPlaylistId,
                    arg<List<MediaItem>>(0).map { it.mediaId },
                    arg<List<MediaItem>>(0).first().localConfiguration?.uri,
                ),
            )
        }
        EnhancedMusicPlayerManager.pendingRadioSeedId = null
        EnhancedMusicPlayerManager.pendingRadioPlaylistId = null
        EnhancedMusicPlayerManager.currentTrackState.value = null
        EnhancedMusicPlayerManager.queueState.value = emptyList()
    }

    @After fun cleanup() {
        stores.forEach(ViewModelStore::clear)
        EnhancedMusicPlayerManager.currentTrackState.value = null
        EnhancedMusicPlayerManager.queueState.value = emptyList()
        EnhancedMusicPlayerManager.pendingRadioSeedId = null
        EnhancedMusicPlayerManager.pendingRadioPlaylistId = null
        savedFields.forEach { (field, value) -> field.set(EnhancedMusicPlayerManager, value) }
        Dispatchers.resetMain()
    }

    private fun viewModel(): MusicPlayerViewModel {
        val downloads = mockk<DownloadManager>(relaxed = true)
        every { downloads.downloadedTracks } returns MutableStateFlow(emptyList())
        val viewModel =
            MusicPlayerViewModel(
                ApplicationProvider.getApplicationContext(),
                mockk(relaxed = true),
                downloads,
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
                mockk(relaxed = true),
            )
        stores.add(ViewModelStore().apply { put("local-playback", viewModel) })
        return viewModel
    }

    @Test fun `local song radio preserves explicit intent and the local URI`() =
        runTest(dispatcher) {
            viewModel().loadAndPlayTrack(track, asRadio = true)
            runCurrent()
            assertThat(requests.single().seed).isEqualTo("local_42")
            assertThat(requests.single().collection).isNull()
            assertThat(requests.single().queue).containsExactly("local_42")
            assertThat(requests.single().uri.toString()).isEqualTo("content://media/external/audio/media/42")
        }

    @Test fun `local collection preserves its first song and collection-start intent`() =
        runTest(dispatcher) {
            viewModel().loadAndPlayTrack(track, listOf(track, other), radioPlaylistId = "local:release:fixture")
            runCurrent()
            assertThat(requests.single().seed).isNull()
            assertThat(requests.single().collection).isEqualTo("local:release:fixture")
            assertThat(requests.single().queue).containsExactly("local_42", "local_43").inOrder()
        }

    @Test fun `collection Play expands an already playing singleton instead of only resuming it`() =
        runTest(dispatcher) {
            every { player.playbackState } returns Player.STATE_READY
            EnhancedMusicPlayerManager.currentTrackState.value = track
            EnhancedMusicPlayerManager.queueState.value = listOf(track)
            viewModel().loadAndPlayTrack(track, listOf(track, other))
            runCurrent()
            assertThat(requests.single().queue).containsExactly("local_42", "local_43").inOrder()
        }

    @Test fun `same local collection resumes without discarding its appended radio`() =
        runTest(dispatcher) {
            every { player.playbackState } returns Player.STATE_READY
            EnhancedMusicPlayerManager.currentTrackState.value = track
            EnhancedMusicPlayerManager.queueState.value =
                listOf(track, other, track.copy(videoId = "youtube-radio", queueOrigin = MusicQueueOrigin.RADIO))
            viewModel().loadAndPlayTrack(track, listOf(track, other))
            runCurrent()
            assertThat(requests).isEmpty()
        }
}
