package io.github.aedev.flow.plugin.playback

import android.app.Application
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.BandwidthMeter
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginVideoSabrDvrTest {
    @Test
    fun `failed renewal retains accepted request and reload token until resolution succeeds`() =
        runTest {
            val provider = mockk<PluginVideoProvider>(relaxed = true)
            val preferences = mockk<PlayerPreferences>(relaxed = true)
            val limits = mockk<VideoDecodeLimits>()
            every { provider.selected } returns "fixture-provider"
            every { provider.playbackContext() } returns "fixture-account"
            coEvery { provider.preparePlaybackContext(any()) } returns "fixture-account"
            every { provider.playbackGrants(any()) } returns listOf("media.example")
            val owner = Any()
            coEvery { provider.playbackLease(any()) } answers { PluginPlaybackLease(owner, { 0L }, {}, {}) }
            every { limits.maxHeight } returns 2160
            every { limits.codecs("auto") } returns listOf("vp9", "h264")
            every { limits.hdr } returns true
            every { preferences.preferredAudioLanguage } returns flowOf("en")
            every { preferences.preferredSubtitleLanguage } returns flowOf("en")
            val presentation =
                ServerAbrPlayback(
                    "https://media.example/sabr",
                    PluginVideoStreamsTest.VIDEO_ID,
                    "AQI",
                    ServerAbrClientInfo(7, "fixture"),
                    listOf(
                        ServerAbrFormat(PluginVideoStreamsTest.audioOriginal.copy(url = ""), 251, "100"),
                        ServerAbrFormat(PluginVideoStreamsTest.video1080.copy(url = ""), 137, "101"),
                    ),
                    durationMs = 212000,
                )
            val response = PluginVideoStreamsTest.playback(VideoKind.VOD).copy(serverAbr = presentation, expiresInMs = 3600000)
            coEvery { provider.resolveBound(any(), any(), any()) } returns response
            val video = PluginVideo(provider, preferences, limits)
            video.resolve(PluginVideoStreamsTest.VIDEO_ID).getOrThrow()
            video.failed(PluginVideoStreamsTest.VIDEO_ID, presentation.url, null, "opaque-reload", ServerAbrFailure.PLAYBACK_CONTEXT_RELOAD)
            val requests = mutableListOf<ResolveVideoRequest>()
            coEvery { provider.resolveBound(any(), any(), any()) } answers {
                requests += thirdArg<ResolveVideoRequest>()
                if (requests.size == 1) throw java.io.IOException("temporary network failure")
                response
            }
            assertThat(video.resolve(PluginVideoStreamsTest.VIDEO_ID).isFailure).isTrue()
            every { preferences.preferredAudioLanguage } returns flowOf("nl")
            video.resolve(PluginVideoStreamsTest.VIDEO_ID).getOrThrow()
            assertThat(requests[1]).isEqualTo(requests[0])
            assertThat(requests[1].failure!!.reloadPlaybackContext).isEqualTo("opaque-reload")
            every { provider.playbackContext() } returns "next-account"
            coEvery { provider.preparePlaybackContext(any()) } returns "next-account"
            video.resolve(PluginVideoStreamsTest.VIDEO_ID).getOrThrow()
            assertThat(requests.last().failure).isNull()
        }

    @Test
    fun `accepted outer DVR capability reaches the prepared bound SABR timeline`() =
        runTest {
            for ((kind, dvr) in listOf(VideoKind.LIVE to false, VideoKind.LIVE to true, VideoKind.VOD to false)) {
                val provider = mockk<PluginVideoProvider>(relaxed = true)
                val preferences = mockk<PlayerPreferences>(relaxed = true)
                val limits = mockk<VideoDecodeLimits>()
                every { provider.selected } returns "fixture-provider"
                every { provider.playbackContext() } returns "fixture-account"
                coEvery { provider.preparePlaybackContext(any()) } returns "fixture-account"
                every { provider.playbackGrants(any()) } returns listOf("media.example")
                every { limits.maxHeight } returns 2160
                every { limits.codecs("auto") } returns listOf("vp9", "h264")
                every { limits.hdr } returns true
                every { preferences.preferredAudioLanguage } returns flowOf("en")
                every { preferences.preferredSubtitleLanguage } returns flowOf("en")
                val entered = CountDownLatch(1)
                val owner = Any()
                coEvery { provider.playbackLease(any()) } answers
                    { PluginPlaybackLease(owner, { 0L }, {}, {}).also { entered.countDown() } }
                val fixture = PluginVideoStreamsTest.playback(kind)
                val presentation =
                    ServerAbrPlayback(
                        "https://media.example/sabr",
                        PluginVideoStreamsTest.VIDEO_ID,
                        "AQI",
                        ServerAbrClientInfo(7, "fixture"),
                        listOf(
                            ServerAbrFormat(PluginVideoStreamsTest.audioOriginal.copy(url = ""), 251, "100"),
                            ServerAbrFormat(PluginVideoStreamsTest.video1080.copy(url = ""), 137, "101"),
                        ),
                        durationMs = 212000,
                        live =
                            kind == VideoKind.LIVE,
                    )
                coEvery { provider.resolveBound(any(), any(), any()) } returns
                    fixture.copy(serverAbr = presentation, dvr = dvr, expiresInMs = 3600000)
                val plugin = PluginVideo(provider, preferences, limits)
                val accepted = plugin.resolve(PluginVideoStreamsTest.VIDEO_ID).getOrThrow()
                assertThat(accepted.dvr).isEqualTo(dvr)
                val item =
                    MediaItem
                        .Builder()
                        .setMediaId("original-catalog-id")
                        .setUri("https://fixture.example/accepted")
                        .build()
                val source = requireNotNull(plugin.bindServerAbr(accepted)).createMediaSource(item)
                assertThat(source.mediaItem).isSameInstanceAs(item)
                val timelines = mutableListOf<Timeline>()
                val caller = MediaSource.MediaSourceCaller { _, timeline -> timelines += timeline }
                source.prepareSource(caller, PlayerId.UNSET, BandwidthMeter.NO_OP)
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue()
                for (attempt in 0 until 100) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    if (timelines.isNotEmpty()) break
                    Thread.sleep(5)
                }
                source.maybeThrowSourceInfoRefreshError()
                assertThat(timelines).isNotEmpty()
                val window = timelines.last().getWindow(0, Timeline.Window())
                assertThat(window.isDynamic).isEqualTo(kind == VideoKind.LIVE)
                assertThat(window.isSeekable).isEqualTo(kind == VideoKind.VOD || dvr)
                source.releaseSource(caller)
            }
        }
}
