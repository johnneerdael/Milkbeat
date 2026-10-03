package io.github.aedev.flow.ui.screens.player

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerManager
import io.github.aedev.flow.player.state.EnhancedPlayerState
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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
 * Pins the fetch economy of [PlayerSecondaryMetadataLoader]: one related request per load, a repeat
 * while that request is in flight dropped, and every related list leaving through
 * [io.github.aedev.flow.player.PlayerRelatedVideosPolicy].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerSecondaryMetadataLoaderTest {
    private val testDispatcher = StandardTestDispatcher()
    private val repository: RelatedFetcher = mockk(relaxed = true)
    private val playerManager: EnhancedPlayerManager = mockk(relaxed = true)
    private val playerState = MutableStateFlow(EnhancedPlayerState())
    private val loaderScope = CoroutineScope(testDispatcher)

    private var uiState = VideoPlayerUiState()
    private var currentToken = TOKEN_A
    private var shortsEnabled = true
    private val results = mutableListOf<SecondaryMetadata>()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        every { playerManager.playerState } returns playerState
        every { playerManager.relatedCandidatesFor(any()) } returns emptyList()
        coEvery { repository.getRelatedCandidates(any()) } returns emptyList()
    }

    @After
    fun tearDown() {
        loaderScope.cancel()
        Dispatchers.resetMain()
        unmockkAll()
    }

    private fun loader(): PlayerSecondaryMetadataLoader =
        PlayerSecondaryMetadataLoader(
            playerManager = playerManager,
            scope = loaderScope,
            networkDispatcher = testDispatcher,
            currentState = { uiState },
            relatedVideosFor = { videoId ->
                uiState
                    .takeIf { it.cachedVideo?.id == videoId }
                    ?.relatedVideos
                    .orEmpty()
            },
            shortsEnabled = { shortsEnabled },
            isPlaybackCurrent = { it == currentToken },
            onResult = { results += it },
            fetchRelated = { videoId -> repository.getRelatedCandidates(videoId) },
        )

    private fun playing(videoId: String) {
        playerState.value = EnhancedPlayerState(currentVideoId = videoId, isPlaying = true)
    }

    private fun relatedResults() = results.filterIsInstance<SecondaryMetadata.Related>()

    @Test
    fun `related candidates are sanitised before they are published`() =
        runTest(testDispatcher) {
            shortsEnabled = false
            val loader = loader()

            loader.loadRelatedVideos(
                videoId = "v1",
                primaryCandidates =
                    listOf(
                        video("v1"),
                        video(""),
                        video("v2"),
                        video("v2"),
                        video("v3", isShort = true),
                        video("v4"),
                    ),
                loadToken = TOKEN_A,
            )
            advanceUntilIdle()

            assertThat(relatedResults().single().videos.map { it.id }).containsExactly("v2", "v4").inOrder()
            coVerify(exactly = 0) { repository.getRelatedCandidates(any()) }
        }

    @Test
    fun `the related fallback request runs once per load and is dropped while in flight`() =
        runTest(testDispatcher) {
            coEvery { repository.getRelatedCandidates("v1") } returns listOf(video("v2"))
            val loader = loader()

            playing("v1")
            repeat(3) { loader.loadRelatedVideos("v1", primaryCandidates = emptyList(), loadToken = TOKEN_A) }
            advanceUntilIdle()

            coVerify(exactly = 1) { repository.getRelatedCandidates("v1") }
            assertThat(relatedResults().single().videos.map { it.id }).containsExactly("v2")
        }

    private companion object {
        const val TOKEN_A = 1L

        fun video(
            id: String,
            isShort: Boolean = false,
        ): Video =
            Video(
                id = id,
                title = "Title $id",
                channelName = "Channel",
                channelId = "channel",
                thumbnailUrl = "https://example.invalid/$id.jpg",
                duration = 120,
                viewCount = 1L,
                uploadDate = "2026-01-01",
                isShort = isShort,
            )
    }
}
