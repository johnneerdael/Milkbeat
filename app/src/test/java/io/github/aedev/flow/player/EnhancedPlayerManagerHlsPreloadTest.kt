package io.github.aedev.flow.player

import android.content.Context
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.model.Video
import io.github.aedev.flow.player.media.MediaLoader
import io.github.aedev.flow.player.preload.PreloadedNext
import io.github.aedev.flow.player.recovery.ClearedMediaRecoveryState
import io.github.aedev.flow.player.stream.ResolvedStreamData
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EnhancedPlayerManagerHlsPreloadTest {
    private val manager =
        EnhancedPlayerManager::class.java
            .getDeclaredConstructor()
            .apply { isAccessible = true }
            .newInstance()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val hls = "https://media.example/vod/master.m3u8"
    private val data =
        ResolvedStreamData(
            Video(
                id = "next",
                title = "Next",
                channelName = "Channel",
                channelId = "UC1",
                thumbnailUrl = "https://media.example/cover.jpg",
                duration = 120,
                viewCount = 0,
                uploadDate = "today",
            ),
            null,
            null,
            emptyList(),
            emptyList(),
            emptyList(),
            120,
            null,
            null,
            emptyList(),
            "auto",
            hlsUrl = hls,
        )

    @After
    fun stopScope() {
        (field(manager, "scope") as CoroutineScope).cancel()
        GlobalPlayerState.setCurrentVideo(null)
    }

    @Test
    fun `the manager builds an HLS-only VOD preload through the native HLS source`() {
        set(manager, "mediaLoader", MediaLoader(context, MutableStateFlow(EnhancedPlayerState()), null, null))
        val preload = field(manager, "preload")!!

        @Suppress("UNCHECKED_CAST")
        val build = field(preload, "buildMediaSource") as (ResolvedStreamData, Context) -> MediaSource?
        assertThat(build(data, context)).isInstanceOf(HlsMediaSource::class.java)
    }

    @Test
    fun `promoted HLS-only VOD reloads its accepted manifest after media is cleared`() {
        val player = mockk<ExoPlayer>(relaxed = true)
        every { player.playbackState } returns Player.STATE_IDLE
        every { player.mediaItemCount } returns 0
        val loader = mockk<MediaLoader>(relaxed = true)
        every {
            loader.loadMedia(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        } returns true
        set(manager, "player", player)
        set(manager, "mediaLoader", loader)
        set(field(manager, "preload")!!, "preloaded", PreloadedNext(data, false))
        invoke("promotePreloadedItem")
        (field(manager, "clearedMediaRecoveryState") as ClearedMediaRecoveryState).capture("next", 42000, true, null)
        assertThat(invoke("reloadClearedMediaIfNeeded", arrayOf(java.lang.Boolean::class.java), arrayOf(true))).isEqualTo(true)
        verify {
            loader.loadMedia(
                player = player,
                context = any(),
                videoStream = null,
                audioStream = null,
                availableVideoStreams = emptyList(),
                currentVideoStream = null,
                dashManifestUrl = null,
                hlsUrl = hls,
                isLiveStream = false,
                durationSeconds = 120,
                currentDurationSeconds = 120,
                preservePosition = 42000,
                localFilePath = null,
                audioOnly = false,
                playWhenReady = true,
                subtitleStreams = any(),
                mediaId = any(),
                mediaMetadata = any(),
                requestHeaders = any(),
                serverAbr = null,
            )
        }
    }

    private fun field(
        owner: Any,
        name: String,
    ): Any? =
        owner.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(owner)

    private fun set(
        owner: Any,
        name: String,
        value: Any?,
    ) {
        owner.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(owner, value)
    }

    private fun invoke(
        name: String,
        types: Array<Class<*>> = emptyArray(),
        args: Array<Any?> = emptyArray(),
    ): Any? =
        manager.javaClass
            .getDeclaredMethod(name, *types)
            .apply { isAccessible = true }
            .invoke(manager, *args)
}
