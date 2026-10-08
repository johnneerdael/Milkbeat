package io.github.aedev.flow.plugin.playback

import android.app.Application
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.download.DownloadUtil
import io.github.aedev.flow.plugin.registry.PluginRegistryState
import io.github.aedev.flow.plugin.registry.ProviderSelection
import io.github.aedev.flow.service.Media3MusicService
import io.github.aedev.flow.service.handlePlayerError
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import nl.neerdael.milkbeat.plugin.FormatType
import nl.neerdael.milkbeat.plugin.MediaFormat
import nl.neerdael.milkbeat.plugin.PluginOperations
import nl.neerdael.milkbeat.plugin.ServerAbrClientInfo
import nl.neerdael.milkbeat.plugin.ServerAbrFormat
import nl.neerdael.milkbeat.plugin.ServerAbrPlayback
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class PluginSabrPictureRecoveryTest : PluginAudioFixture() {
    private val mediaId = "catalog-id"
    private val position = 42500L
    private val native =
        ServerAbrPlayback(
            "https://cdn.example/sabr",
            candidate.ref.providerId,
            "fixture",
            ServerAbrClientInfo(7, "fixture"),
            listOf(ServerAbrFormat(MediaFormat("251", FormatType.AUDIO, "", "audio/webm", codecs = "opus"), 251, "100")),
        )

    init {
        every { registry.state } returns
            kotlinx.coroutines.flow.MutableStateFlow(
                PluginRegistryState(
                    listOf(
                        plugin.copy(
                            grantedNetwork = listOf("cdn.example"),
                            manifest =
                                plugin.manifest.copy(
                                    roles =
                                        plugin.manifest.roles.copy(
                                            audio =
                                                plugin.manifest.roles.audio!!
                                                    .copy(musicVideo = true),
                                        ),
                                ),
                        ),
                    ),
                    ProviderSelection(audio = listOf("youtube")),
                ),
            )
        coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns
            stream.copy(url = native.url, mimeType = "application/x-server-abr", serverAbr = native)
    }

    private fun service(): Pair<Media3MusicService, MutableList<MediaItem>> {
        val items =
            mutableListOf(
                MediaItem
                    .Builder()
                    .setMediaId(mediaId)
                    .setUri("musicvideo://$mediaId?provider=youtube&descriptor=fake-preserved-descriptor")
                    .build(),
            )
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.currentMediaItemIndex } returns 0
        every { player.currentPosition } returns position
        every { player.mediaItemCount } answers { items.size }
        every { player.currentMediaItem } answers { items.first() }
        every { player.getMediaItemAt(any()) } answers { items[firstArg()] }
        every { player.replaceMediaItem(any(), any()) } answers {
            items[firstArg()] = secondArg()
            Unit
        }
        val service = Robolectric.buildService(Media3MusicService::class.java).get()
        service.player = player
        service.pluginAudio = audio
        service.downloadUtil = mockk<DownloadUtil>(relaxed = true)
        return service to items
    }

    @Test
    fun `missing native picture falls back at the same position despite a cached accepted native song`() =
        runTest {
            val accepted = audio.resolve(original, null, playbackId = mediaId)
            val unavailable =
                runCatching {
                    audio.resolve(
                        original,
                        PictureLimits(2160, listOf("av1")),
                        playbackId = mediaId,
                    )
                }.exceptionOrNull()
            assertThat(unavailable).isNotNull()
            val (service, items) = service()
            val acceptedUri = items.single().localConfiguration!!.uri
            service.handlePlayerError(PlaybackException("fixture", unavailable, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), 0)
            assertThat(
                items
                    .single()
                    .localConfiguration!!
                    .uri.scheme,
            ).isEqualTo("music")
            assertThat(items.single().mediaId).isEqualTo(mediaId)
            assertThat(
                items
                    .single()
                    .localConfiguration!!
                    .uri
                    .getQueryParameter("provider"),
            ).isEqualTo(acceptedUri.getQueryParameter("provider"))
            verify { service.player.seekTo(0, position) }
            assertThat(service.retryCountMap).isEmpty()
            assertThat(audio.resolve(original, null, playbackId = mediaId).track.ref).isEqualTo(accepted.track.ref)
            service.pendingRetryJob?.cancel()
        }

    @Test
    fun `real native no-progress renews the picture source at the same position`() =
        runTest {
            val withPicture =
                native.copy(
                    formats =
                        native.formats +
                            ServerAbrFormat(
                                MediaFormat(
                                    "399",
                                    FormatType.VIDEO,
                                    "",
                                    "video/mp4",
                                    codecs = "av01.0.12M.08",
                                    width = 3840,
                                    height = 2160,
                                ),
                                399,
                                "101",
                            ),
                )
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns
                stream.copy(url = native.url, mimeType = "application/x-server-abr", serverAbr = withPicture)
            audio.resolve(original, PictureLimits(2160, listOf("av1")), playbackId = mediaId)
            val (service, items) = service()
            val acceptedUri = items.single().localConfiguration!!.uri
            val failure =
                nl.neerdael.milkbeat.sabr.SabrPlaybackException(
                    nl.neerdael.milkbeat.sabr.SabrPlaybackException.Reason.NO_PROGRESS,
                    native.url,
                    null,
                )
            service.handlePlayerError(PlaybackException("fixture", failure, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), 0)
            assertThat(service.retryCountMap[mediaId]).isEqualTo(1)
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(3))
            assertThat(
                items
                    .single()
                    .localConfiguration!!
                    .uri.scheme,
            ).isEqualTo("musicvideo")
            assertThat(items.single().localConfiguration!!.uri).isEqualTo(acceptedUri)
            verify { service.player.seekTo(0, position) }
            val renewal = slot<nl.neerdael.milkbeat.plugin.ResolveAudioRequest>()
            coEvery { host.call("youtube", PluginOperations.resolveAudio, capture(renewal)) } returns
                stream.copy(url = native.url, mimeType = "application/x-server-abr", serverAbr = withPicture)
            audio.resolve(original, PictureLimits(2160, listOf("av1")), playbackId = mediaId)
            assertThat(renewal.captured.track.ref).isEqualTo(candidate.ref)
            assertThat(renewal.captured.video).isTrue()
            assertThat(renewal.captured.failure!!.serverAbrFailure).isEqualTo(nl.neerdael.milkbeat.plugin.ServerAbrFailure.NO_PROGRESS)
            service.pendingRetryJob?.cancel()
        }
}
