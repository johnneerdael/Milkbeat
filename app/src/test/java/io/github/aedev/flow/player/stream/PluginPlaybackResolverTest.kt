package io.github.aedev.flow.player.stream

import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.data.repository.SponsorBlockRepository
import io.github.aedev.flow.data.video.DownloadedVideo
import io.github.aedev.flow.data.video.VideoDownloadManager
import io.github.aedev.flow.plugin.catalog.NoVideoPluginException
import io.github.aedev.flow.plugin.playback.PluginVideo
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.VIDEO_ID
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.playback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.VideoKind
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Pins what one plugin resolve turns into for the player screen, and that a download never asks the plugin. */
class PluginPlaybackResolverTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val testDispatcher = StandardTestDispatcher()
    private val pluginVideo: PluginVideo = mockk(relaxed = true)
    private val downloads = MutableStateFlow<List<DownloadedVideo>>(emptyList())
    private val videoDownloadManager: VideoDownloadManager = mockk(relaxed = true)
    private val sponsorBlockRepository: SponsorBlockRepository = mockk(relaxed = true)
    private val resolver = PluginPlaybackResolver(pluginVideo, videoDownloadManager, sponsorBlockRepository, testDispatcher)

    init {
        every { videoDownloadManager.downloadedVideos } returns downloads
        coEvery { videoDownloadManager.getSponsorBlockData(any()) } returns null
    }

    private suspend fun steps(resumeMs: Long? = null): List<ResolvedPlayback> {
        val steps = mutableListOf<ResolvedPlayback>()
        resolver.resolve(
            request =
                PlaybackResolutionRequest(
                    VIDEO_ID,
                    resumePositionOverrideMs = resumeMs,
                    allowShorts = true,
                ),
            cached = null,
            isCurrent = { true },
            onStep = { steps += it },
        )
        return steps
    }

    @Test
    fun `a VOD becomes one plugin step carrying the resume override`() =
        runTest(testDispatcher) {
            coEvery { pluginVideo.resolve(VIDEO_ID) } returns Result.success(playback())

            val step = steps(resumeMs = 30_000).single() as ResolvedPlayback.FromPlugin

            assertThat(step.resumePositionOverrideMs).isEqualTo(30_000)
            assertThat(step.playable.videoStreams).hasSize(1)
            assertThat(step.playable.audioStreams).hasSize(1)
        }

    @Test
    fun `a downloaded copy plays without asking the plugin`() =
        runTest(testDispatcher) {
            val file = folder.newFile("$VIDEO_ID.mp4")
            downloads.value =
                listOf(DownloadedVideo(Video(VIDEO_ID, "", "", "", "", duration = 0, viewCount = 0L, uploadDate = ""), file.absolutePath))

            val step = steps().single() as ResolvedPlayback.LocalCopyReady

            assertThat(step.localFilePath).isEqualTo(file.absolutePath)
            assertThat(step.needsSponsorBlockBackfill).isTrue()
            coVerify(exactly = 0) { pluginVideo.resolve(any()) }
        }

    @Test
    fun `a failing plugin, or none at all, is handed on as a plugin failure`() =
        runTest(testDispatcher) {
            coEvery { pluginVideo.resolve(VIDEO_ID) } returns Result.failure(NoVideoPluginException())

            val step = steps().single() as ResolvedPlayback.PluginFailed

            assertThat(step.cause).isInstanceOf(NoVideoPluginException::class.java)
        }

    @Test
    fun `a premiere becomes the countdown at its start time`() {
        val step =
            PluginPlaybackResolver.stepFor(
                playback(kind = VideoKind.UPCOMING, formats = emptyList()).copy(startsInMs = 90_000),
                cached = null,
                resumePositionOverrideMs = null,
                nowMs = 1_000_000,
            ) as ResolvedPlayback.Upcoming

        assertThat(step.releaseTimeMs).isEqualTo(1_090_000)
        assertThat(step.details?.title).isEqualTo("Plugin title")
        assertThat(step.details?.channelId).isEqualTo("UCplugin")
    }

    @Test
    fun `SABR-only VOD is a playable native resolution without direct formats`() {
        val native =
            nl.neerdael.milkbeat.plugin.ServerAbrPlayback(
                "https://media.example/sabr",
                "fixture",
                "dXBzdHJlYW0=",
                nl.neerdael.milkbeat.plugin
                    .ServerAbrClientInfo(7, "fixture"),
                listOf(
                    nl.neerdael.milkbeat.plugin.ServerAbrFormat(
                        nl.neerdael.milkbeat.plugin.MediaFormat(
                            "251",
                            nl.neerdael.milkbeat.plugin.FormatType.AUDIO,
                            "",
                            "audio/webm",
                            codecs = "opus",
                        ),
                        251,
                        "123",
                    ),
                ),
            )
        val step = PluginPlaybackResolver.stepFor(playback(formats = emptyList()).copy(serverAbr = native), null, null)
        assertThat(step).isInstanceOf(ResolvedPlayback.FromPlugin::class.java)
        assertThat((step as ResolvedPlayback.FromPlugin).playable.serverAbr).isSameInstanceAs(native)
    }

    @Test
    fun `a VOD with nothing to play is an extraction failure`() {
        val step = PluginPlaybackResolver.stepFor(playback(formats = emptyList()), cached = null, resumePositionOverrideMs = null)

        assertThat((step as ResolvedPlayback.Failed).failure).isEqualTo(PlaybackFailure.EXTRACTION)
    }

    @Test
    fun `a live stream plays from its manifest`() {
        val step =
            PluginPlaybackResolver.stepFor(
                playback(kind = VideoKind.LIVE, formats = emptyList()).copy(dashUrl = "https://live.invalid/a.mpd", dvr = true),
                cached = null,
                resumePositionOverrideMs = null,
            ) as ResolvedPlayback.FromPlugin

        assertThat(step.playable.isLive).isTrue()
        assertThat(step.playable.dashUrl).isEqualTo("https://live.invalid/a.mpd")
    }
}
