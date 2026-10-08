package io.github.aedev.flow.plugin.playback

import android.app.Application
import android.content.Context
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.EnhancedPlayerState
import io.github.aedev.flow.player.media.MediaLoader
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.VIDEO_ID
import io.github.aedev.flow.plugin.playback.PluginVideoStreamsTest.Companion.playback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.VideoKind
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.schabi.newpipe.extractor.stream.StreamType

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class PluginVideoStreamSourceTest {
    private val plugin = mockk<PluginVideo>(relaxed = true)
    private val preferences = mockk<PlayerPreferences>()
    private val source = PluginVideoStreamSource(plugin, preferences)
    private val cached =
        Video(
            id = VIDEO_ID,
            title = "Cached",
            channelName = "Cached channel",
            channelId = "UCcached",
            thumbnailUrl = "https://media.example/cached.jpg",
            duration = 1,
            viewCount = 0,
            uploadDate = "today",
        )

    init {
        every { preferences.preferredAudioLanguage } returns flowOf("en")
        every { plugin.bindServerAbr(any()) } returns null
        coEvery { plugin.related(VIDEO_ID) } returns emptyList()
    }

    @Test
    fun `DASH-only VOD survives queue resolution and native preload without fabricated streams`() =
        runTest {
            val dash = "https://media.example/vod.mpd"
            val headers = mapOf("X-Fixture-Profile" to "accepted")
            coEvery { plugin.resolve(VIDEO_ID) } returns
                Result.success(playback(formats = emptyList(), headers = headers).copy(dashUrl = dash))
            val data = source.resolve(cached)!!
            assertThat(data.dashManifestUrl).isEqualTo(dash)
            assertThat(data.streamType).isEqualTo(StreamType.VIDEO_STREAM)
            assertThat(data.enrichedVideo.id).isEqualTo(VIDEO_ID)
            assertThat(data.enrichedVideo.title).isEqualTo("Plugin title")
            assertThat(data.enrichedVideo.channelId).isEqualTo("UCplugin")
            assertThat(data.durationSeconds).isEqualTo(212)
            assertThat(data.videoStreams).isEmpty()
            assertThat(data.audioStreams).isEmpty()
            assertThat(data.requestHeaders.forUrl(dash)).isEqualTo(headers)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val metadata = MediaMetadata.Builder().setTitle(data.enrichedVideo.title).build()
            val prepared =
                MediaLoader(context, MutableStateFlow(EnhancedPlayerState()), null, null).buildPreloadMediaSource(
                    context,
                    data.videoStream,
                    data.audioStream,
                    data.videoStreams,
                    data.dashManifestUrl,
                    data.durationSeconds,
                    mediaId = data.enrichedVideo.id,
                    mediaMetadata = metadata,
                    requestHeaders = data.requestHeaders,
                )
            assertThat(prepared).isInstanceOf(DashMediaSource::class.java)
            assertThat(prepared!!.mediaItem.mediaId).isEqualTo(VIDEO_ID)
            assertThat(
                prepared.mediaItem.mediaMetadata.title
                    .toString(),
            ).isEqualTo("Plugin title")
            coVerify(exactly = 1) { plugin.resolve(VIDEO_ID) }
        }

    @Test
    fun `live DASH retains its manifest and live classification through queue resolution`() =
        runTest {
            val dash = "https://media.example/live.mpd"
            coEvery { plugin.resolve(VIDEO_ID) } returns
                Result.success(playback(VideoKind.LIVE, formats = emptyList()).copy(dashUrl = dash))
            val data = source.resolve(cached)!!
            assertThat(data.dashManifestUrl).isEqualTo(dash)
            assertThat(data.streamType).isEqualTo(StreamType.LIVE_STREAM)
            assertThat(data.enrichedVideo.isLive).isTrue()
            assertThat(data.videoStreams).isEmpty()
        }
}
