package io.github.aedev.flow.plugin.playback

import android.app.Application
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.local.PlayerPreferences
import io.github.aedev.flow.plugin.catalog.PluginVideoProvider
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.*
import nl.neerdael.milkbeat.sabr.SabrPlaybackException
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginSabrExpiryTest : PluginAudioFixture() {
    private fun native(id: String) =
        ServerAbrPlayback(
            "https://media.example/sabr",
            id,
            "AQI",
            ServerAbrClientInfo(7, "fixture"),
            listOf(ServerAbrFormat(MediaFormat("251", FormatType.AUDIO, "", "audio/webm", codecs = "opus"), 251, "100")),
            durationMs = 120000,
        )

    // Capture the real factory's pre-request guard without making an external HTTP request.
    private fun inspectGuard(
        expired: Boolean,
        prepare: () -> Unit,
    ) {
        val guards = mutableListOf<() -> Unit>()
        mockkStatic("io.github.aedev.flow.plugin.playback.PluginSabrDataSourceKt")
        try {
            every { pluginSabrDataSourceFactory(any(), any(), any(), any()) } answers {
                guards += thirdArg<() -> Unit>()
                callOriginal()
            }
            prepare()
            assertThat(guards).hasSize(1)
            val failure = runCatching { guards.single().invoke() }.exceptionOrNull()
            if (expired) {
                assertThat(failure).isInstanceOf(SabrPlaybackException::class.java)
                assertThat((failure as SabrPlaybackException).reason).isEqualTo(SabrPlaybackException.Reason.URL_EXPIRED)
            } else {
                assertThat(failure).isNull()
            }
        } finally {
            unmockkStatic("io.github.aedev.flow.plugin.playback.PluginSabrDataSourceKt")
        }
    }

    @Test
    fun `prepared audio expires at the buffered deadline while the provider URL is still valid`() =
        runTest {
            every { registry.state } returns
                MutableStateFlow(
                    PluginRegistryState(
                        listOf(plugin.copy(grantedNetwork = listOf("media.example"))),
                        ProviderSelection(audio = listOf("youtube")),
                    ),
                )
            for (lifetime in listOf(30000L, 120000L)) {
                coEvery { host.call("youtube", PluginOperations.resolveAudio, any()) } returns
                    stream.copy(
                        url = "https://media.example/sabr",
                        mimeType = "application/x-server-abr",
                        serverAbr = native(candidate.ref.providerId),
                        expiresInMs = lifetime,
                    )
                audio.failed(original.ref.providerId, "https://media.example/sabr", null)
                val accepted = audio.resolve(original, null)
                inspectGuard(lifetime < 60000L) { audio.serverAbrDataSourceFactory(accepted, okhttp3.OkHttpClient()) }
            }
        }

    @Test
    fun `prepared video expires at the buffered deadline while the provider URL is still valid`() =
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
            val fixture = PluginVideoStreamsTest.playback(VideoKind.VOD)
            val presentation =
                native(PluginVideoStreamsTest.VIDEO_ID).copy(
                    formats =
                        listOf(
                            ServerAbrFormat(PluginVideoStreamsTest.audioOriginal.copy(url = ""), 251, "100"),
                            ServerAbrFormat(PluginVideoStreamsTest.video1080.copy(url = ""), 137, "101"),
                        ),
                )
            for (lifetime in listOf(30000L, 120000L)) {
                coEvery { provider.resolveBound(any(), any(), any()) } returns
                    fixture.copy(serverAbr = presentation, expiresInMs = lifetime)
                val video = PluginVideo(provider, preferences, limits)
                val accepted = video.resolve(PluginVideoStreamsTest.VIDEO_ID).getOrThrow()
                inspectGuard(lifetime < 60000L) { requireNotNull(video.bindServerAbr(accepted)) }
            }
        }
}
