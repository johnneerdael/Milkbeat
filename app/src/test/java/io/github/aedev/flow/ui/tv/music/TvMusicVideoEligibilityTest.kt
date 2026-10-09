package io.github.aedev.flow.ui.tv.music

import android.app.Application
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.NowPlayingView
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.EnhancedMusicPlayerManager
import io.github.aedev.flow.player.MusicPlayerState
import io.github.aedev.flow.player.preparedMusicPictureTracks
import io.github.aedev.flow.player.setVideoCapablePlaybackIds
import io.github.aedev.flow.plugin.playback.RadioTuningState
import io.github.aedev.flow.ui.screens.music.MusicPlayerViewModel
import io.github.aedev.flow.ui.tv.theme.TvTheme
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w1280dp-h720dp-land-mdpi")
class TvMusicVideoEligibilityTest {
    @get:Rule val compose = createComposeRule()
    private val manager = EnhancedMusicPlayerManager

    @Before
    fun setup() {
        val player = mockk<Player>(relaxed = true)
        every { player.currentMediaItem } answers {
            manager.currentTrack.value?.let { MediaItem.Builder().setMediaId(it.videoId).build() }
        }
        every { player.currentTracks } returns preparedMusicPictureTracks()
        every { player.trackSelectionParameters } returns TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        manager.javaClass
            .getDeclaredField("player")
            .apply { isAccessible = true }
            .set(null, player)
    }

    @After
    fun cleanup() {
        manager.currentTrackState.value = null
        manager.queueState.value = emptyList()
        manager.playbackState.value = MusicPlayerState()
        manager.setVideoCapablePlaybackIds(emptySet())
        manager.setVideoMode(false)
        manager.javaClass
            .getDeclaredField("player")
            .apply { isAccessible = true }
            .set(null, null)
    }

    @Test
    fun `the view button offers confirmed video for SoundCloud metadata without changing its identity`() {
        val track = MusicTrack("soundcloud:tracks:42", "Live set", "Artist", "", 3600)
        manager.currentTrackState.value = track
        manager.playbackState.value = MusicPlayerState()
        manager.setVideoCapablePlaybackIds(emptySet())
        var chosen: NowPlayingView? = null
        val viewModel = mockk<MusicPlayerViewModel>(relaxed = true)
        every { viewModel.radioTuning.state } returns MutableStateFlow(RadioTuningState())
        compose.setContent {
            val input = LocalInputModeManager.current
            SideEffect { input.requestInputMode(InputMode.Keyboard) }
            TvTheme {
                TvMusicNowPlayingScreen(
                    viewModel,
                    onCollapse = {},
                    visualizer = TvNowPlayingVisual({}, {}, {}),
                    onViewChange = { chosen = it },
                )
            }
        }
        compose.onNodeWithContentDescription("Show artwork").assertExists()
        compose.runOnIdle { manager.setVideoCapablePlaybackIds(setOf(track.videoId)) }
        compose.onNodeWithContentDescription("Show video").assertExists().performClick()
        compose.runOnIdle {
            assertThat(chosen).isEqualTo(NowPlayingView.VIDEO)
            assertThat(manager.currentTrack.value).isEqualTo(track)
            manager.setVideoCapablePlaybackIds(emptySet())
        }
        compose.onNodeWithContentDescription("Show artwork").assertExists()
    }
}
