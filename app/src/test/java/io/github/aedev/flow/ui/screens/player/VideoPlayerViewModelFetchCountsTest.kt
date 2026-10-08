package io.github.aedev.flow.ui.screens.player

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.R
import io.github.aedev.flow.data.model.Comment
import io.github.aedev.flow.player.GlobalPlayerState
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModelHarness.Companion.video
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import nl.neerdael.milkbeat.catalog.CommentsPage
import nl.neerdael.milkbeat.plugin.ServerAbrFailure
import nl.neerdael.milkbeat.plugin.StreamFailure
import nl.neerdael.milkbeat.plugin.VideoPlayback
import org.junit.After
import org.junit.Before
import org.junit.Test
import nl.neerdael.milkbeat.catalog.Comment as PluginComment

/**
 * Pins how many times each network entry point is entered per user-visible cause, with the video
 * plugin failing every resolve. Nothing here is a spec: every count is today's behaviour, recorded so
 * a refactor can prove it did not change it.
 *
 * Entry points counted:
 *  - the video plugin: [io.github.aedev.flow.plugin.playback.PluginVideo.resolve]
 *  - the in-app YouTube stack, which the player no longer reaches: InnerTube extraction, the Return
 *    YouTube Dislike gate and the premiere probe ([YouTube.player])
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerViewModelFetchCountsTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var harness: VideoPlayerViewModelHarness

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        harness = VideoPlayerViewModelHarness(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        harness.close()
    }

    private fun TestScope.newViewModel(): VideoPlayerViewModel {
        val viewModel = harness.createViewModel()
        advanceUntilIdle()
        return viewModel
    }

    private fun forgetRecordedCalls() {
        clearAllMocks(answers = false, childMocks = false)
    }

    @Test
    fun `playVideo resets state then makes 1 plugin resolve and no YouTube call`() =
        runTest {
            val viewModel = newViewModel()
            val video = video("vid_a")

            viewModel.uiState.test {
                assertThat(awaitItem().cachedVideo).isNull()

                viewModel.playVideo(video)

                val reset = expectMostRecentItem()
                assertThat(reset.cachedVideo).isEqualTo(video)
                assertThat(reset.isLoading).isTrue()
                assertThat(reset.error).isNull()
                assertThat(reset.errorHint).isNull()
                assertThat(reset.relatedVideos).isEmpty()
                assertThat(reset.channelAvatarUrl).isEqualTo(video.channelThumbnailUrl)
                assertThat(reset.isRestoredSession).isFalse()
                assertThat(reset.isBackgroundPlaybackMode).isFalse()
                assertThat(reset.shouldDismissPlayer).isFalse()
                assertThat(reset.localFilePath).isNull()

                advanceUntilIdle()

                val terminal = expectMostRecentItem()
                assertThat(terminal.cachedVideo).isEqualTo(video)
                assertThat(terminal.isLoading).isFalse()
                assertThat(terminal.error).isEqualTo("res:${R.string.error_generic}")
                assertThat(terminal.errorHint).isEqualTo("res:${R.string.error_generic_hint}")
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { harness.pluginVideo.resolve("vid_a") }
            verifyOrder {
                harness.playerManager.pause()
                harness.playerManager.clearAll()
                harness.playerManager.startBackgroundService("vid_a", video.title, video.channelName, video.thumbnailUrl)
            }
            verify(exactly = 1) { GlobalPlayerState.setCurrentVideo(video) }
            coVerify(exactly = 0) { harness.playerPreferences.markVideoUnplayable(any()) }
        }

    @Test
    fun `retryLoadVideo clears the player drops the cached resolve and resolves once more`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()
            forgetRecordedCalls()

            viewModel.retryLoadVideo()

            val retrying = viewModel.uiState.value
            assertThat(retrying.isLoading).isTrue()
            assertThat(retrying.error).isNull()
            assertThat(retrying.errorHint).isNull()
            verify(exactly = 1) { harness.playerManager.clearCurrentVideo() }
            verify(exactly = 1) { harness.pluginVideo.forget("vid_a") }

            advanceUntilIdle()

            val terminal = viewModel.uiState.value
            assertThat(terminal.isLoading).isFalse()
            assertThat(terminal.error).isEqualTo("res:${R.string.error_generic}")
            coVerify(exactly = 1) { harness.pluginVideo.resolve("vid_a") }
            verify(exactly = 0) { harness.playerManager.pause() }
            verify(exactly = 0) { harness.playerManager.clearAll() }
        }

    @Test
    fun `stream expiry tells the plugin which URL failed and resolves once more`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()
            forgetRecordedCalls()
            every { harness.playerManager.lastStreamHttpFailure } returns ("https://cdn.invalid/a" to 403)

            assertThat(harness.streamExpiredEvent.tryEmit(Unit)).isTrue()
            runCurrent()
            advanceUntilIdle()

            val terminal = viewModel.uiState.value
            assertThat(terminal.isLoading).isFalse()
            assertThat(terminal.error).isEqualTo("res:${R.string.error_generic}")
            assertThat(terminal.errorHint).isEqualTo("res:${R.string.error_generic_hint}")
            coVerifyOrder {
                harness.pluginVideo.failed("vid_a", "https://cdn.invalid/a", 403)
                harness.pluginVideo.resolve("vid_a")
            }
            coVerify(exactly = 1) { harness.pluginVideo.resolve("vid_a") }
            coVerify(exactly = 0) { harness.playerManager.clearCacheForCurrentVideo() }
            coVerify(exactly = 0) { harness.playerPreferences.markVideoUnplayable(any()) }
        }

    @Test
    fun `SABR reload preserves its opaque context without reporting a stale HTTP denial`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()
            forgetRecordedCalls()
            every { harness.playerManager.lastStreamHttpFailure } returns ("https://cdn.invalid/stale" to 403)
            every { harness.playerManager.lastServerAbrFailure } returns
                StreamFailure(
                    url = "https://cdn.invalid/abr",
                    reloadPlaybackContext = "opaque-reload-context",
                    serverAbrFailure = ServerAbrFailure.PLAYBACK_CONTEXT_RELOAD,
                )

            assertThat(harness.streamExpiredEvent.tryEmit(Unit)).isTrue()
            advanceUntilIdle()

            coVerifyOrder {
                harness.pluginVideo.failed(
                    "vid_a",
                    "https://cdn.invalid/abr",
                    null,
                    "opaque-reload-context",
                    ServerAbrFailure.PLAYBACK_CONTEXT_RELOAD,
                )
                harness.pluginVideo.resolve("vid_a")
            }
            coVerify(exactly = 1) { harness.pluginVideo.resolve("vid_a") }
            verify(exactly = 0) { harness.pluginVideo.failed("vid_a", "https://cdn.invalid/stale", 403) }
            verify(exactly = 0) { harness.pluginVideo.forget("vid_a") }
        }

    @Test
    fun `stream expiry without an HTTP failure only drops the cached resolve`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()
            forgetRecordedCalls()

            assertThat(harness.streamExpiredEvent.tryEmit(Unit)).isTrue()
            advanceUntilIdle()

            verify(exactly = 1) { harness.pluginVideo.forget("vid_a") }
            verify(exactly = 0) { harness.pluginVideo.failed(any(), any(), any()) }
            coVerify(exactly = 1) { harness.pluginVideo.resolve("vid_a") }
        }

    @Test
    fun `stream expiry gives up after MAX_STREAM_EXPIRY_RETRIES and marks the video unplayable`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()

            repeat(2) {
                assertThat(harness.streamExpiredEvent.tryEmit(Unit)).isTrue()
                advanceUntilIdle()
            }
            coVerify(exactly = 1) { harness.playerManager.clearCacheForCurrentVideo() }
            forgetRecordedCalls()

            assertThat(harness.streamExpiredEvent.tryEmit(Unit)).isTrue()
            advanceUntilIdle()
            coVerify(exactly = 1) { harness.playerManager.clearCacheForCurrentVideo() }
            forgetRecordedCalls()

            assertThat(harness.streamExpiredEvent.tryEmit(Unit)).isTrue()
            advanceUntilIdle()

            val terminal = viewModel.uiState.value
            assertThat(terminal.isLoading).isFalse()
            assertThat(terminal.error).isEqualTo("res:${R.string.error_all_stream_sources_failed}")
            assertThat(terminal.errorHint).isEqualTo("res:${R.string.error_playback_retry_hint}")
            coVerify(exactly = 1) { harness.playerPreferences.markVideoUnplayable("vid_a") }
            coVerify(exactly = 0) { harness.pluginVideo.resolve(any()) }

            assertThat(harness.streamExpiredEvent.tryEmit(Unit)).isTrue()
            advanceUntilIdle()
            coVerify(exactly = 0) { harness.pluginVideo.resolve(any()) }
        }

    @Test
    fun `a queue moves past a video whose streams could not be recovered instead of stopping (#1008)`() =
        runTest {
            every { harness.playerManager.skipAbandonedVideo() } returns true
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()

            repeat(4) {
                assertThat(harness.streamExpiredEvent.tryEmit(Unit)).isTrue()
                advanceUntilIdle()
            }

            verify(exactly = 1) { harness.playerManager.skipAbandonedVideo() }
            assertThat(viewModel.uiState.value.error).isNotEqualTo("res:${R.string.error_all_stream_sources_failed}")
        }

    @Test
    fun `player reporting a foreign video id triggers a load without any cached metadata`() =
        runTest {
            val viewModel = newViewModel()

            harness.playerState.value = EnhancedPlayerState(currentVideoId = "ext_1")
            advanceUntilIdle()

            val terminal = viewModel.uiState.value
            assertThat(terminal.cachedVideo).isNull()
            assertThat(terminal.isLoading).isFalse()
            assertThat(terminal.error).isEqualTo("res:${R.string.error_generic}")
            coVerify(exactly = 1) { harness.pluginVideo.resolve("ext_1") }
            verify(exactly = 0) { harness.playerManager.startBackgroundService(any(), any(), any(), any()) }
        }

    @Test
    fun `a superseded load is cancelled and its outcome never reaches the ui`() =
        runTest {
            val viewModel = newViewModel()
            val videoA = video("vid_a")
            val videoB = video("vid_b")
            val gateA = CompletableDeferred<Result<VideoPlayback>>()
            val gateB = CompletableDeferred<Result<VideoPlayback>>()
            coEvery { harness.pluginVideo.resolve("vid_a") } coAnswers { gateA.await() }
            coEvery { harness.pluginVideo.resolve("vid_b") } coAnswers { gateB.await() }

            viewModel.uiState.test {
                awaitItem()

                viewModel.playVideo(videoA)
                runCurrent()
                assertThat(expectMostRecentItem().cachedVideo).isEqualTo(videoA)

                viewModel.playVideo(videoB)
                runCurrent()
                val loadingB = expectMostRecentItem()
                assertThat(loadingB.cachedVideo).isEqualTo(videoB)
                assertThat(loadingB.isLoading).isTrue()
                assertThat(loadingB.error).isNull()

                gateA.complete(Result.failure(RuntimeException("A failed")))
                runCurrent()
                expectNoEvents()
                assertThat(viewModel.uiState.value.cachedVideo).isEqualTo(videoB)
                assertThat(viewModel.uiState.value.isLoading).isTrue()

                gateB.complete(Result.failure(IllegalStateException()))
                advanceUntilIdle()
                val terminal = expectMostRecentItem()
                assertThat(terminal.cachedVideo).isEqualTo(videoB)
                assertThat(terminal.isLoading).isFalse()
                assertThat(terminal.errorHint).isEqualTo("res:${R.string.error_generic_hint}")
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 1) { harness.pluginVideo.resolve("vid_a") }
            coVerify(exactly = 1) { harness.pluginVideo.resolve("vid_b") }
        }

    @Test
    fun `loadComments drops a second request for the same id while the first is in flight`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            val comment =
                Comment(
                    id = "c1",
                    author = "author",
                    authorThumbnail = "",
                    text = "first",
                    likeCount = 0,
                    publishedTime = "",
                )
            coEvery { harness.pluginVideo.comments(any()) } coAnswers {
                gate.await()
                Result.success(CommentsPage(comments = listOf(PluginComment(id = "c1", author = "author", text = "first"))))
            }

            viewModel.loadComments("vid_a")
            runCurrent()
            assertThat(viewModel.isLoadingComments.value).isTrue()
            coVerify(exactly = 1) { harness.pluginVideo.comments(any()) }

            viewModel.loadComments("vid_a")
            runCurrent()
            coVerify(exactly = 1) { harness.pluginVideo.comments(any()) }
            assertThat(viewModel.isLoadingComments.value).isTrue()

            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(viewModel.commentsState.value).containsExactly(comment)
            assertThat(viewModel.isLoadingComments.value).isFalse()
            assertThat(viewModel.hasMoreComments.value).isFalse()
        }

    @Test
    fun `loadComments for a video that is not the cached one never reaches the repository`() =
        runTest {
            val viewModel = newViewModel()
            viewModel.playVideo(video("vid_a"))
            advanceUntilIdle()

            viewModel.loadComments("vid_other")
            advanceUntilIdle()

            coVerify(exactly = 0) { harness.pluginVideo.comments(any()) }
            assertThat(viewModel.isLoadingComments.value).isFalse()
        }
}
