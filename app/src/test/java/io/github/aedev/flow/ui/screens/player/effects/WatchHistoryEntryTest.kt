package io.github.aedev.flow.ui.screens.player.effects

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.ui.screens.player.VideoPlayerViewModel
import io.github.aedev.flow.ui.screens.player.state.VideoPlayerUiState
import io.github.aedev.flow.ui.screens.player.state.applyLiveStreams
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test
import org.schabi.newpipe.extractor.Image

/**
 * The watch-history write is described in exactly one place so the 3 s save, the 10 s loop and the
 * player host's dispose block cannot drift apart again. This pins that one description.
 */
class WatchHistoryEntryTest {
    private fun video(
        id: String = "vid_1",
        title: String = "Cached title",
        channelName: String = "Cached channel",
        channelId: String = "cached_channel",
        thumbnailUrl: String = "https://example.invalid/cached.jpg",
        isShort: Boolean = false,
    ): Video =
        Video(
            id = id,
            title = title,
            channelName = channelName,
            channelId = channelId,
            thumbnailUrl = thumbnailUrl,
            duration = 120,
            viewCount = 1L,
            uploadDate = "2026-01-01",
            isShort = isShort,
        )

    private fun image(
        url: String,
        height: Int,
    ): Image = Image(url, height, Image.WIDTH_UNKNOWN, Image.ResolutionLevel.UNKNOWN)

    private fun entry(
        uiState: VideoPlayerUiState,
        video: Video = video(),
        position: Long = 30_000L,
        duration: Long = 120_000L,
    ) = buildWatchHistoryEntry(video, uiState, position, duration)

    @Test
    fun `a collaboration keeps the cached channel name`() {
        listOf("Alice and Bob", "Alice & Bob", "Alice x Bob", "Alice with Bob").forEach { cached ->
            val result = entry(VideoPlayerUiState(), video = video(channelName = cached))

            assertThat(result!!.channelName).isEqualTo(cached)
        }
    }

    @Test
    fun `the entry is built from the cached video everywhere`() {
        val result = entry(VideoPlayerUiState())

        assertThat(result!!.title).isEqualTo("Cached title")
        assertThat(result.channelName).isEqualTo("Cached channel")
        assertThat(result.channelId).isEqualTo("cached_channel")
        assertThat(result.thumbnailUrl).isEqualTo("https://example.invalid/cached.jpg")
    }

    @Test
    fun `an empty cached thumbnail falls back to the youtube still`() {
        val result = entry(VideoPlayerUiState(), video = video(thumbnailUrl = ""))

        assertThat(result!!.thumbnailUrl).isEqualTo("https://i.ytimg.com/vi/vid_1/hq720.jpg")
    }

    @Test
    fun `a live stream is never written`() {
        assertThat(entry(VideoPlayerUiState(isLive = true, hlsUrl = "https://example.invalid/manifest.m3u8"))).isNull()
    }

    @Test
    fun `SABR live playback without a manifest URL records a live visit instead of resumable history`() {
        val state = VideoPlayerUiState().applyLiveStreams(emptyList(), hlsUrl = null)
        val current = video()
        val viewModel = mockk<VideoPlayerViewModel>(relaxed = true)
        assertThat(state.isCurrentLiveStream()).isTrue()
        assertThat(entry(state, duration = 120_000L)).isNull()
        saveWatchProgress(viewModel, current, state, 30_000L, 120_000L)
        verify(exactly = 1) { viewModel.trackLivePlayback(current, 30_000L) }
        verify(exactly = 0) { viewModel.savePlaybackPosition(any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `VOD HLS and a new load after live retain resumable history`() {
        val vod = VideoPlayerUiState(isLive = false, hlsUrl = "https://example.invalid/vod.m3u8")
        assertThat(entry(vod)).isNotNull()
        val next = VideoPlayerUiState(isLive = true).resetForVideo(video())
        assertThat(next.isCurrentLiveStream()).isFalse()
        assertThat(entry(next)).isNotNull()
    }

    @Test
    fun `a zero duration or an empty title is never written`() {
        assertThat(entry(VideoPlayerUiState(), duration = 0L)).isNull()
        assertThat(entry(VideoPlayerUiState(), duration = -1L)).isNull()
        assertThat(entry(VideoPlayerUiState(), video = video(title = ""))).isNull()
    }

    @Test
    fun `the short flag comes from the cached video`() {
        assertThat(entry(VideoPlayerUiState(), video = video(isShort = true))!!.isShort).isTrue()
    }
}
