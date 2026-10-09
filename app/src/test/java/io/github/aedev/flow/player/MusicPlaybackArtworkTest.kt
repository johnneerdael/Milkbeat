package io.github.aedev.flow.player

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.datasource.PluginMusicDataSourceFactory
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import kotlinx.coroutines.CompletableDeferred
import nl.neerdael.milkbeat.catalog.Artwork
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlaybackArtworkTest {
    private val manager = EnhancedMusicPlayerManager

    @Test
    fun `only the playing accepted source supplies artwork while queue metadata stays original`() {
        val first = track("soundcloud:42")
        val second = track("spotify:99")
        val oldResolution = CompletableDeferred<ResolvedAudio>()
        val delegate = DataSource.Factory { ByteArrayDataSource(ByteArray(256)) }
        val dataSource =
            PluginMusicDataSourceFactory(
                delegate,
                resolve = { uri, _ -> if (uri.authority == first.videoId) oldResolution.await() else audio(second.videoId) },
                bind = { _, _ -> delegate },
            )
        val player =
            ExoPlayer
                .Builder(RuntimeEnvironment.getApplication())
                .setMediaSourceFactory(MusicMediaSourceFactory(DefaultMediaSourceFactory(delegate), dataSource) { false })
                .build()
        manager.queueState.value = listOf(first, second)
        // Exercise the same aggregate-event consumer used by the MediaController. Its snapshot is
        // selected by Media3, never by whichever queued resolution finishes last.
        manager.javaClass
            .getDeclaredMethod("setupPlayerListener", androidx.media3.common.Player::class.java)
            .apply { isAccessible = true }
            .invoke(manager, player)
        try {
            player.setMediaItems(listOf(item(first), item(second)))
            player.prepare()
            player.seekTo(1, 0)
            await { player.mediaMetadata.artworkUri.toString() == "https://playback.example/${second.videoId}.jpg" }
            await { manager.playbackArtwork.value?.mediaId == second.videoId }
            assertEquals("https://playback.example/${second.videoId}.jpg", manager.playbackArtwork.value?.url)
            assertEquals(second, manager.currentTrack.value)
            assertEquals(second.title, player.mediaMetadata.title)
            assertEquals(second.artist, player.mediaMetadata.artist)
            assertNull(manager.playbackArtwork.value?.forTrack(first.videoId))

            oldResolution.complete(audio(first.videoId))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("https://playback.example/${second.videoId}.jpg", manager.playbackArtwork.value?.forTrack(second.videoId))
            assertEquals("https://catalog.example/cover.jpg", manager.currentTrack.value?.thumbnailUrl)

            player.clearMediaItems()
            await { manager.playbackArtwork.value == null }
        } finally {
            player.release()
            oldResolution.cancel()
            manager.queueState.value = emptyList()
            manager.currentTrackState.value = null
            manager.playbackArtworkState.value = null
        }
    }

    private fun track(id: String) = MusicTrack(id, "Catalog title $id", "Catalog artist", "https://catalog.example/cover.jpg", 60)

    private fun item(track: MusicTrack) =
        MediaItem
            .Builder()
            .setUri("music://${track.videoId}")
            .setMediaId(track.videoId)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setArtworkUri(Uri.parse(track.thumbnailUrl))
                    .build(),
            ).build()

    private fun audio(id: String) =
        ResolvedAudio(
            "youtube-video",
            TrackDescriptor(EntityRef(EntityKind.TRACK, "accepted-$id"), "Matched title"),
            AudioStream(
                "https://media.example/audio",
                id,
                "aac",
                "audio/mp4",
                artwork = Artwork("https://playback.example/$id.jpg", 1920, 1080),
            ),
            Long.MAX_VALUE,
            false,
        )

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue(condition())
    }
}
