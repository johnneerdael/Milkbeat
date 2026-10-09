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
import io.github.aedev.flow.service.resetRecoveredRetryBudget
import io.github.aedev.flow.service.triggerRetryAfterNetworkRestore
import io.github.aedev.flow.utils.NetworkConnectivityObserver
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
            durationMs = 120000,
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
        service.connectivityObserver = mockk<NetworkConnectivityObserver>()
        every { service.connectivityObserver.checkCurrentConnectivity() } returns true
        every { service.downloadUtil.invalidateUrlCache(mediaId) } answers { audio.forget(mediaId) }
        return service to items
    }

    @Test
    fun `online typed renewal reasons retain protocol priority and reload context`() =
        runTest {
            for (reason in nl.neerdael.milkbeat.sabr.SabrPlaybackException.Reason.entries) {
                // Each reason is an independent recovery episode with its own service retry budget.
                audio.forgetAll()
                audio.resolve(original, null, playbackId = mediaId)
                val (service, _) = service()
                val context =
                    if (reason ==
                        nl.neerdael.milkbeat.sabr.SabrPlaybackException.Reason.PLAYBACK_CONTEXT_RELOAD
                    ) {
                        "opaque-fixture"
                    } else {
                        null
                    }
                val error =
                    nl.neerdael.milkbeat.sabr
                        .SabrPlaybackException(reason, native.url, context)
                service.handlePlayerError(PlaybackException("fixture", error, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED), 0)
                assertThat(service.waitingForNetwork).isFalse()
                assertThat(service.pendingNetworkRetry).isNull()
                assertThat(service.retryCountMap[mediaId]).isEqualTo(1)
                service.pendingRetryJob?.cancel()
                val renewal = slot<nl.neerdael.milkbeat.plugin.ResolveAudioRequest>()
                coEvery { host.call("youtube", PluginOperations.resolveAudio, capture(renewal)) } returns
                    stream.copy(url = native.url, mimeType = "application/x-server-abr", serverAbr = native)
                audio.resolve(original, null, playbackId = mediaId)
                assertThat(
                    renewal.captured.failure!!
                        .serverAbrFailure!!
                        .name,
                ).isEqualTo(reason.name)
                assertThat(renewal.captured.failure!!.reloadPlaybackContext).isEqualTo(context)
            }
        }

    @Test
    fun `online native connection timeout uses maintained network retry instead of protocol renewal`() =
        runTest {
            audio.resolve(original, null, playbackId = mediaId)
            val (service, items) = service()
            val uri = items.single().localConfiguration!!.uri
            service.handlePlayerError(
                PlaybackException("fixture", java.io.IOException("fixture"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT),
                0,
            )
            assertThat(service.pendingNetworkRetry!!.mediaId).isEqualTo(mediaId)
            assertThat(service.waitingForNetwork).isFalse()
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(3))
            verify(exactly = 0) { service.player.seekTo(any<Int>(), any<Long>()) }
            assertThat(items.single().localConfiguration!!.uri).isEqualTo(uri)
            service.pendingRetryJob?.cancel()
        }

    @Test
    fun `offline native playback waits for connectivity with accepted view and position`() =
        runTest {
            audio.resolve(original, null, playbackId = mediaId)
            val (service, items) = service()
            val uri = items.single().localConfiguration!!.uri
            every { service.connectivityObserver.checkCurrentConnectivity() } returns false
            service.handlePlayerError(
                PlaybackException("fixture", java.io.IOException("fixture"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED),
                0,
            )
            assertThat(service.waitingForNetwork).isTrue()
            assertThat(service.pendingNetworkRetry!!.mediaId).isEqualTo(mediaId)
            assertThat(service.pendingNetworkRetry!!.refreshNativeSource).isTrue()
            assertThat(service.pendingNetworkRetry!!.resumePositionMs).isEqualTo(position)
            assertThat(service.pendingRetryJob).isNull()
            assertThat(items.single().localConfiguration!!.uri).isEqualTo(uri)
            every { service.connectivityObserver.checkCurrentConnectivity() } returns true
            service.waitingForNetwork = false
            service.triggerRetryAfterNetworkRestore()
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(1))
            verify { service.player.seekTo(0, position) }
            verify(exactly = 0) { service.player.seekToNextMediaItem() }
            assertThat(items.single().localConfiguration!!.uri).isEqualTo(uri)
            assertThat(service.pendingNetworkRetry).isNull()
            service.pendingRetryJob?.cancel()
        }

    @Test
    fun `offline typed no-progress waits rather than immediately renewing`() =
        runTest {
            audio.resolve(original, null, playbackId = mediaId)
            val (service, _) = service()
            every { service.connectivityObserver.checkCurrentConnectivity() } returns false
            val error =
                nl.neerdael.milkbeat.sabr.SabrPlaybackException
                    .noProgress(native.url, java.io.EOFException("truncated fixture"))
            service.handlePlayerError(PlaybackException("fixture", error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), 0)
            assertThat(service.waitingForNetwork).isTrue()
            assertThat(service.pendingNetworkRetry!!.mediaId).isEqualTo(mediaId)
            assertThat(service.pendingRetryJob).isNull()
            every { service.connectivityObserver.checkCurrentConnectivity() } returns true
            service.triggerRetryAfterNetworkRestore()
            org.robolectric.Shadows
                .shadowOf(android.os.Looper.getMainLooper())
                .idleFor(java.time.Duration.ofSeconds(1))
            verify { service.player.replaceMediaItem(0, any()) }
            verify { service.player.seekTo(0, position) }
            val renewal = slot<nl.neerdael.milkbeat.plugin.ResolveAudioRequest>()
            coEvery { host.call("youtube", PluginOperations.resolveAudio, capture(renewal)) } returns
                stream.copy(url = native.url, mimeType = "application/x-server-abr", serverAbr = native)
            audio.resolve(original, null, playbackId = mediaId)
            assertThat(renewal.captured.track.ref).isEqualTo(candidate.ref)
            assertThat(renewal.captured.failure!!.serverAbrFailure).isEqualTo(nl.neerdael.milkbeat.plugin.ServerAbrFailure.NO_PROGRESS)
            service.pendingRetryJob?.cancel()
        }

    @Test
    fun `brief READY after native renewal retains its budget until repeated failures terminate`() =
        runTest {
            audio.resolve(original, null, playbackId = mediaId)
            val (service, _) = service()
            val error =
                nl.neerdael.milkbeat.sabr.SabrPlaybackException(
                    nl.neerdael.milkbeat.sabr.SabrPlaybackException.Reason.NO_PROGRESS,
                    native.url,
                    null,
                )
            for (attempt in 1..5) {
                service.handlePlayerError(PlaybackException("fixture", error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), 0)
                service.resetRecoveredRetryBudget(mediaId)
                assertThat(service.retryCountMap[mediaId]).isEqualTo(attempt)
            }
            service.handlePlayerError(PlaybackException("fixture", error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), 0)
            assertThat(service.recentlyFailedSongs).contains(mediaId)
            service.pendingRetryJob?.cancel()
        }

    @Test
    fun `stable READY after the existing grace window clears native renewal budget`() =
        runTest {
            audio.resolve(original, null, playbackId = mediaId)
            val (service, _) = service()
            val error =
                nl.neerdael.milkbeat.sabr.SabrPlaybackException(
                    nl.neerdael.milkbeat.sabr.SabrPlaybackException.Reason.NO_PROGRESS,
                    native.url,
                    null,
                )
            service.handlePlayerError(PlaybackException("fixture", error, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), 0)
            val lastError = service.lastPlaybackErrorAtMap.getValue(mediaId)
            service.resetRecoveredRetryBudget(mediaId, lastError + Media3MusicService.RECOVERY_SUCCESS_GRACE_MS + 1)
            assertThat(service.retryCountMap).isEmpty()
            assertThat(service.lastPlaybackErrorAtMap).isEmpty()
            service.pendingRetryJob?.cancel()
        }

    @Test
    fun `strict audio HLS answer falls back at the same position despite a cached native song`() =
        runTest {
            val accepted = audio.resolve(original, null, playbackId = mediaId)
            coEvery { host.call("youtube", PluginOperations.resolveAudio, match { it.track.ref == candidate.ref }) } returns
                stream.copy(url = "https://cdn.example/master.m3u8", mimeType = "application/x-mpegURL", requireAudioOnlyHls = true)
            val unavailable =
                runCatching {
                    audio.resolve(original, PictureLimits(2160, listOf("av1")), playbackId = mediaId)
                }.exceptionOrNull()
            assertThat(unavailable).isNotNull()
            val (service, items) = service()
            service.handlePlayerError(PlaybackException("fixture", unavailable, PlaybackException.ERROR_CODE_IO_UNSPECIFIED), 0)
            assertThat(
                items
                    .single()
                    .localConfiguration!!
                    .uri.scheme,
            ).isEqualTo("music")
            assertThat(items.single().mediaId).isEqualTo(mediaId)
            verify { service.player.seekTo(0, position) }
            assertThat(service.retryCountMap).isEmpty()
            val song = audio.resolve(original, null, playbackId = mediaId)
            assertThat(song.track.ref).isEqualTo(accepted.track.ref)
            assertThat(song.stream.requireAudioOnlyHls).isTrue()
            assertThat(song.withPicture).isFalse()
            service.pendingRetryJob?.cancel()
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
