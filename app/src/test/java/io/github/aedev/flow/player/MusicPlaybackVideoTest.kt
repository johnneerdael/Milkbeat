package io.github.aedev.flow.player

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.service.Media3MusicService
import io.github.aedev.flow.service.fallBackToSong
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.PluginJson
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlaybackVideoTest {
    private val manager = EnhancedMusicPlayerManager
    private val player = mockk<ExoPlayer>(relaxed = true)
    private val descriptor =
        TrackDescriptor(
            EntityRef(EntityKind.TRACK, "soundcloud:tracks:42"),
            "Live set",
            ids =
                mapOf("soundcloud" to "42"),
        )
    private val track =
        MusicTrack(
            descriptor.ref.providerId,
            descriptor.title,
            "Artist",
            "",
            3600,
            descriptor = PluginJson.encodeToString(TrackDescriptor.serializer(), descriptor),
        )
    private val items = mutableListOf<MediaItem>()
    private var selection = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT

    @Before
    fun setup() {
        manager.javaClass
            .getDeclaredField("player")
            .apply { isAccessible = true }
            .set(null, player)
        manager.showVideo = false
        manager.videoSurfaces = 0
        manager.streamItemIds.clear()
        manager.videoItemIds.clear()
        manager.videoUnavailableIds.clear()
        manager.queueState.value = listOf(track)
        manager.currentTrackState.value = track
        items += manager.buildMediaItem(track)
        every { player.currentMediaItemIndex } returns 0
        every { player.currentPosition } returns 12_345L
        every { player.mediaItemCount } answers { items.size }
        every { player.getMediaItemAt(any()) } answers { items[firstArg()] }
        every { player.replaceMediaItem(any(), any()) } answers { items[firstArg()] = secondArg() }
        every { player.trackSelectionParameters } answers { selection }
        every { player.trackSelectionParameters = any() } answers { selection = firstArg() }
    }

    @After
    fun cleanup() {
        manager.showVideo = false
        manager.videoSurfaces = 0
        manager.streamItemIds.clear()
        manager.videoItemIds.clear()
        manager.videoUnavailableIds.clear()
        manager.currentTrackState.value = null
        manager.queueState.value = emptyList()
        manager.setVideoCapablePlaybackIds(emptySet())
        manager.javaClass
            .getDeclaredField("player")
            .apply { isAccessible = true }
            .set(null, null)
    }

    private fun capability(ids: Set<String>) = manager.setVideoCapablePlaybackIds(ids)

    @Test
    fun `confirmed capability keeps initial playback audio-only until the viewer switches`() {
        capability(setOf(track.videoId))

        assertThat(manager.videoAvailable.value).isTrue()
        assertThat(manager.streamUri(track).scheme).isEqualTo(MusicVideoItems.SONG_SCHEME)
        verify(exactly = 0) { player.replaceMediaItem(any(), any()) }
        manager.setVideoMode(true)
        val switched = items.single()
        assertThat(switched.localConfiguration!!.uri.scheme).isEqualTo(MusicVideoItems.SCHEME)
        assertThat(switched.mediaId).isEqualTo(track.videoId)
        assertThat(MusicVideoItems.descriptor(switched.localConfiguration!!.uri)).isEqualTo(descriptor)
        verify(exactly = 1) { player.seekTo(0, 12_345L) }
    }

    @Test
    fun `revoked or unrelated capability cannot offer the previous source video`() {
        capability(setOf(track.videoId))
        capability(setOf("previous-track"))
        assertThat(manager.videoAvailable.value).isFalse()
        manager.setVideoMode(true)

        assertThat(manager.streamUri(track).scheme).isEqualTo(MusicVideoItems.SONG_SCHEME)
        verify(exactly = 0) { player.replaceMediaItem(any(), any()) }
    }

    @Test
    fun `unavailable picture remains audio-only after capability is republished`() {
        capability(setOf(track.videoId))
        manager.onVideoUnavailable(track.videoId)
        assertThat(manager.videoAvailable.value).isFalse()
        capability(setOf(track.videoId))
        manager.setVideoMode(true)

        assertThat(manager.streamUri(track).scheme).isEqualTo(MusicVideoItems.SONG_SCHEME)
        verify(exactly = 0) { player.replaceMediaItem(any(), any()) }
    }

    @Test
    fun `a matched Art Track disables picture decoding when its surface is hidden`() {
        capability(setOf(track.videoId))
        manager.setVideoMode(true)
        assertThat(selection.disabledTrackTypes).contains(C.TRACK_TYPE_VIDEO)
        manager.acquireVideoSurface()
        assertThat(selection.disabledTrackTypes).doesNotContain(C.TRACK_TYPE_VIDEO)
        manager.releaseVideoSurface()
        assertThat(selection.disabledTrackTypes).contains(C.TRACK_TYPE_VIDEO)
    }

    @Test
    fun `missing picture recovery preserves the source identity and playback position`() {
        capability(setOf(track.videoId))
        manager.setVideoMode(true)
        val service =
            Media3MusicService().apply {
                player = this@MusicPlaybackVideoTest.player
                downloadUtil = mockk(relaxed = true)
            }
        val failed = MusicPlaybackRecoveryPlanner.FailedItem(0, track.videoId, 12_345L)

        assertThat(service.fallBackToSong(failed)).isTrue()

        val recovered = items.single()
        assertThat(recovered.mediaId).isEqualTo(track.videoId)
        assertThat(recovered.localConfiguration!!.uri.scheme).isEqualTo(MusicVideoItems.SONG_SCHEME)
        assertThat(MusicVideoItems.descriptor(recovered.localConfiguration!!.uri)).isEqualTo(descriptor)
        assertThat(manager.videoAvailable.value).isFalse()
        assertThat(manager.videoShown.value).isFalse()
        verify(exactly = 2) { player.seekTo(0, 12_345L) }
        verify(exactly = 1) { player.prepare() }
        verify(exactly = 1) { player.play() }
    }

    @Test
    fun `an explicit Video selection rebinds newly confirmed audio even when Video was remembered`() {
        manager.setVideoMode(true)
        capability(setOf(track.videoId))
        assertThat(
            items
                .single()
                .localConfiguration!!
                .uri.scheme,
        ).isEqualTo(MusicVideoItems.SONG_SCHEME)

        manager.setVideoMode(true)
        manager.setVideoMode(true)

        assertThat(
            items
                .single()
                .localConfiguration!!
                .uri.scheme,
        ).isEqualTo(MusicVideoItems.SCHEME)
        verify(exactly = 1) { player.replaceMediaItem(0, any()) }
        verify(exactly = 1) { player.seekTo(0, 12_345L) }
    }

    @Test
    fun `selecting Video without a playing queue never addresses an unset index`() {
        every { player.currentMediaItemIndex } returns C.INDEX_UNSET
        every { player.mediaItemCount } returns 0
        manager.currentTrackState.value = null
        manager.queueState.value = emptyList()

        manager.setVideoMode(true)

        verify(exactly = 0) { player.getMediaItemAt(any()) }
        verify(exactly = 0) { player.replaceMediaItem(any(), any()) }
        assertThat(manager.videoAvailable.value).isFalse()
    }
}
