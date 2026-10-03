package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.SponsorBlockSegment
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.stream.PlaybackFailure
import io.github.aedev.flow.player.stream.ResolvedPlayback
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModelHarness.Companion.video
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Pins what one resolved step lands on: the state the screen holds afterwards, and what reaches the
 * player manager, the stream hand-off and the secondary loaders.
 *
 * The collaborators are mocks so the sequence itself is what is asserted; the state assertions are
 * the reducers' output seen through the flow the ViewModel shares with the applier. What each
 * reducer writes field by field is pinned in `PlayerPlaybackReducersTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionApplierTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var harness: VideoPlayerViewModelHarness

    private val uiState = MutableStateFlow(VideoPlayerUiState())
    private val playbackPreparer: PlaybackPreparer = mockk(relaxed = true)
    private val secondaryMetadata: PlayerSecondaryMetadataLoader = mockk(relaxed = true)

    private val enteredUpcoming = mutableListOf<Triple<String, Long?, List<Video>>>()

    @Before
    fun setUp() {
        kotlinx.coroutines.Dispatchers.setMain(testDispatcher)
        harness = VideoPlayerViewModelHarness(testDispatcher)
    }

    @After
    fun tearDown() {
        kotlinx.coroutines.Dispatchers.resetMain()
        harness.close()
    }

    private fun CoroutineScope.applier(): PlaybackSessionApplier =
        PlaybackSessionApplier(
            context = harness.context,
            uiState = uiState,
            isLoadCurrent = { token -> token == CURRENT_TOKEN },
            playbackPreparer = playbackPreparer,
            pluginPlayback = mockk(relaxed = true),
            secondaryMetadata = secondaryMetadata,
            viewHistory = harness.viewHistory,
            playerPreferences = harness.playerPreferences,
            sponsorBlockRepository = harness.sponsorBlockRepository,
            videoDownloadManager = harness.videoDownloadManager,
            offlineSubtitleStore = harness.offlineSubtitleStore,
            playerManager = harness.playerManager,
            scope = this,
            networkDispatcher = testDispatcher,
            enterUpcoming = { videoId, releaseMs, relatedVideos, _, _ ->
                enteredUpcoming += Triple(videoId, releaseMs, relatedVideos)
                true
            },
        )

    @Test
    fun `a downloaded copy with no stored segments fetches them once and publishes them`() =
        runTest(testDispatcher) {
            val segments = listOf(segment())
            coEvery { harness.sponsorBlockRepository.getSegments(VIDEO_ID) } returns segments
            coEvery { harness.sponsorBlockRepository.serializeSegments(segments) } returns "[segments]"

            val step =
                ResolvedPlayback.LocalCopyReady(
                    localFilePath = "/tmp/$VIDEO_ID.mp4",
                    offlineSegments = null,
                    needsSponsorBlockBackfill = true,
                )
            applier().apply(step, load())
            advanceUntilIdle()

            coVerify(exactly = 1) { harness.videoDownloadManager.saveSponsorBlockData(VIDEO_ID, "[segments]") }
            assertThat(uiState.value.offlineSponsorBlockSegments).isEqualTo(segments)
        }

    @Test
    fun `a downloaded copy that already carries its segments fetches nothing`() =
        runTest(testDispatcher) {
            val step =
                ResolvedPlayback.LocalCopyReady(
                    localFilePath = "/tmp/$VIDEO_ID.mp4",
                    offlineSegments = listOf(segment()),
                    needsSponsorBlockBackfill = false,
                )
            applier().apply(step, load())
            advanceUntilIdle()

            coVerify(exactly = 0) { harness.sponsorBlockRepository.getSegments(any()) }
        }

    @Test
    fun `a failure writes the error and leaves a retryable video unmarked`() =
        runTest(testDispatcher) {
            val related = listOf(video("rel_1"))

            applier().apply(
                ResolvedPlayback.Failed(PlaybackFailure.EXTRACTION, RuntimeException("plugin unavailable"), related),
                load(),
            )
            advanceUntilIdle()

            val state = uiState.value
            assertThat(state.isLoading).isFalse()
            assertThat(state.error).isEqualTo("res:${R.string.error_generic}")
            assertThat(state.errorHint).isEqualTo("RuntimeException: plugin unavailable")
            assertThat(state.relatedVideos.map { it.id }).containsExactly("rel_1")
            coVerify(exactly = 0) { harness.playerPreferences.markVideoUnplayable(any()) }
        }

    @Test
    fun `a step from a superseded load writes nothing`() =
        runTest(testDispatcher) {
            applier().apply(
                ResolvedPlayback.Failed(PlaybackFailure.EXTRACTION, RuntimeException("boom"), emptyList()),
                load(token = CURRENT_TOKEN + 1L),
            )
            advanceUntilIdle()

            assertThat(uiState.value).isEqualTo(VideoPlayerUiState())
        }

    @Test
    fun `a ready local copy is written and prepared from the saved position`() =
        runTest(testDispatcher) {
            val segments = listOf(segment())

            applier().apply(ResolvedPlayback.LocalCopyReady("/tmp/a.mp4", segments), load())
            advanceUntilIdle()

            assertThat(uiState.value.localFilePath).isEqualTo("/tmp/a.mp4")
            assertThat(uiState.value.localFileVideoId).isEqualTo(VIDEO_ID)
            coVerify(exactly = 1) {
                playbackPreparer.prepareLocalMedia(VIDEO_ID, "/tmp/a.mp4", segments, 0L, any(), emptyList(), any())
            }
        }

    @Test
    fun `an upcoming step is handed to the premiere entry with what the load resolved`() =
        runTest(testDispatcher) {
            val related = listOf(video("rel_1"))

            applier().apply(ResolvedPlayback.Upcoming(related, releaseTimeMs = 2_500L), load())
            advanceUntilIdle()

            assertThat(enteredUpcoming).containsExactly(Triple(VIDEO_ID, 2_500L, related))
        }

    @Test
    fun `related metadata reaches the autoplay queue and the lane together`() =
        runTest(testDispatcher) {
            uiState.value = VideoPlayerUiState(cachedVideo = video(VIDEO_ID))
            val related = listOf(video("rel_1"))

            applier().applySecondary(SecondaryMetadata.Related(VIDEO_ID, CURRENT_TOKEN, related))
            advanceUntilIdle()

            coVerify(exactly = 1) { harness.playerManager.setAutoplayCandidates(VIDEO_ID, related, true) }
            assertThat(uiState.value.relatedVideos.map { it.id }).containsExactly("rel_1")
        }

    private fun load(token: Long = CURRENT_TOKEN): LoadContext = LoadContext(VIDEO_ID, token)

    private fun segment(): SponsorBlockSegment =
        SponsorBlockSegment(category = "sponsor", segment = listOf(0f, 1f), uuid = "uuid_1", actionType = "skip")

    private companion object {
        const val VIDEO_ID = "vid_applier"
        const val CURRENT_TOKEN = 7L
    }
}
