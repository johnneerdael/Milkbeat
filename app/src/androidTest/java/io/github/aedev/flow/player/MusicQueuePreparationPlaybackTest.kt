package io.github.aedev.flow.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.data.music.model.MusicTrack
import io.github.aedev.flow.player.datasource.PluginMusicDataSourceFactory
import io.github.aedev.flow.plugin.playback.QueuePreparationResult
import io.github.aedev.flow.plugin.playback.ResolvedAudio
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import nl.neerdael.milkbeat.catalog.EntityKind
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import nl.neerdael.milkbeat.plugin.AudioStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicQueuePreparationPlaybackTest {
    @Test
    fun naturalTransitionKeepsCurrentAndDropsOnlyConfirmedMisses(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val clip = File(context.cacheDir, "queue-preparation.aac")
            instrumentation.context.assets
                .open("player/silence.aac")
                .use { input -> clip.outputStream().use(input::copyTo) }
            val url = Uri.fromFile(clip).toString()
            val upstream = DefaultDataSource.Factory(context)
            val sourceResolutions = AtomicInteger()
            val dataSource =
                PluginMusicDataSourceFactory(
                    upstream,
                    { uri, _ ->
                        sourceResolutions.incrementAndGet()
                        ResolvedAudio(
                            "fixture",
                            MusicVideoItems.descriptor(uri),
                            AudioStream(url, "fixture", "aac", "audio/aac"),
                            Long.MAX_VALUE,
                            false,
                        )
                    },
                    { audio, _ -> ResolvingDataSource.Factory(upstream) { spec -> spec.withUri(Uri.parse(audio.stream.url)) } },
                )
            val active = AtomicInteger()
            val peak = AtomicInteger()
            val prepared = Collections.synchronizedList(mutableListOf<String>())
            val transitions = Collections.synchronizedList(mutableListOf<Pair<String, Int>>())
            val buffering = AtomicInteger()
            var firstReady = false
            val manager = EnhancedMusicPlayerManager
            val savedPlayer = manager.player
            val savedPrefetcher = manager.prefetcher
            val player =
                withContext(Dispatchers.Main) {
                    ExoPlayer
                        .Builder(context)
                        .setMediaSourceFactory(MusicMediaSourceFactory(DefaultMediaSourceFactory(context), dataSource) { false })
                        .build()
                        .apply { volume = 0f }
                }
            val session = withContext(Dispatchers.Main) { MediaSession.Builder(context, player).setId("queue-preparation-test").build() }
            val future = withContext(Dispatchers.Main) { MediaController.Builder(context, session.token).buildAsync() }
            val controller = withContext(Dispatchers.IO) { future.get(20, java.util.concurrent.TimeUnit.SECONDS) }
            try {
                withContext(Dispatchers.Main) {
                    field("player", controller)
                    manager.javaClass
                        .getDeclaredMethod(
                            "setupPlayerListener",
                            Player::class.java,
                        ).apply { isAccessible = true }
                        .invoke(manager, controller)
                    manager.prefetcher = { uri ->
                        val count = active.incrementAndGet()
                        peak.updateAndGet { maxOf(it, count) }
                        try {
                            delay(25)
                            val id = uri.authority.orEmpty()
                            prepared += id
                            when (id) {
                                "missing" -> QueuePreparationResult.Unmatched()
                                "temporary" -> QueuePreparationResult.Retryable
                                else -> QueuePreparationResult.Ready
                            }
                        } finally {
                            active.decrementAndGet()
                        }
                    }
                    player.addListener(
                        object : Player.Listener {
                            override fun onMediaItemTransition(
                                mediaItem: MediaItem?,
                                reason: Int,
                            ) {
                                transitions += mediaItem?.mediaId.orEmpty() to reason
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (playbackState == Player.STATE_READY) firstReady = true
                                if (firstReady && playbackState == Player.STATE_BUFFERING) buffering.incrementAndGet()
                            }
                        },
                    )
                    val tracks =
                        (listOf("first", "missing", "second", "temporary", "third") + (5..100).map { "queued-$it" }).map {
                            MusicTrack(it, it, "Fixture", "", 8)
                        }
                    manager.playTrack(tracks.first(), "music://first", tracks)
                }
                withTimeout(25_000) {
                    while (!withContext(Dispatchers.Main) {
                            player.playerError?.let { throw AssertionError("Fixture playback failed", it) }
                            player.currentMediaItem?.mediaId == "second" && player.currentPosition > 400
                        }
                    ) {
                        delay(100)
                    }
                }
                val automaticBuffering = buffering.get()
                val bufferedSources = sourceResolutions.get()
                assertTrue("Media3 should not prepare all 100 sources", bufferedSources < 100)
                withContext(Dispatchers.Main) {
                    player.pause()
                    assertEquals(100, manager.queue.value.size)
                    assertEquals(
                        listOf("first", "second", "temporary", "third"),
                        manager.queue.value
                            .take(4)
                            .map { it.videoId },
                    )
                    assertEquals("second", manager.currentTrack.value?.videoId)
                    assertEquals(1, manager.currentQueueIndex.value)
                    assertFalse(player.playWhenReady)
                    player.repeatMode = Player.REPEAT_MODE_ALL
                    manager.moveMediaItem(3, 2)
                    player.seekTo(0)
                    player.play()
                }
                delay(500)
                withContext(Dispatchers.Main) { player.pause() }
                assertEquals(1, peak.get())
                assertTrue(prepared.containsAll(listOf("missing", "second", "temporary", "third")))
                assertTrue(prepared.contains("queued-100"))
                assertTrue(transitions.contains("second" to Player.MEDIA_ITEM_TRANSITION_REASON_AUTO))
                assertFalse(transitions.any { it.first == "missing" })
                assertEquals(0, automaticBuffering)
                Log.i(
                    "MusicQueuePreparationTest",
                    "Automatic transition verified; peak preparation=$peak; post-start buffering=$automaticBuffering; source resolutions=$bufferedSources",
                )
            } finally {
                withContext(Dispatchers.Main) {
                    manager.prefetcher = savedPrefetcher
                    field("player", savedPlayer)
                    manager.javaClass
                        .getDeclaredField(
                            "positionUpdateJob",
                        ).apply { isAccessible = true }
                        .get(null)
                        ?.let { (it as Job).cancel() }
                    manager.queueState.value = emptyList()
                    manager.currentTrackState.value = null
                    controller.release()
                    session.release()
                    player.release()
                }
                clip.delete()
            }
        }

    private fun field(
        name: String,
        value: Any?,
    ) {
        EnhancedMusicPlayerManager.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(null, value)
    }
}
