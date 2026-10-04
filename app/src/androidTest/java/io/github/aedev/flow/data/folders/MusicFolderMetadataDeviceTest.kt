package io.github.aedev.flow.data.folders

import android.media.MediaMetadataRetriever
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class MusicFolderMetadataDeviceTest {
    @Test fun readsLocalTitleArtistAlbumDurationAndEmbeddedArtwork() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val directory = File(context.cacheDir, "playlists").apply { mkdirs() }
        for (format in listOf("m4a", "mp3", "flac")) {
            val file = File(directory, "metadata.$format")
            instrumentation.context.assets
                .open("folders/tagged.$format")
                .use { input -> file.outputStream().use(input::copyTo) }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, FileProvider.getUriForFile(context, context.packageName + ".files", file))
                assertMetadata(MusicFolderMetadataReader(context, MusicFolderStore(context), testFolderClients()).readTags(retriever))
            } finally {
                retriever.release()
                file.delete()
            }
        }
    }

    @Test fun readsSmbTagsAndArtworkThroughRandomAccessSource(): Unit =
        runBlocking {
            val host = InstrumentationRegistry.getArguments().getString("smb_fixture_host")
            assumeNotNull(host)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = MusicFolderStore(context)
            val source = MusicFolder(name = "Tags", kind = MusicFolderKind.SMB, host = host!!, share = "Music", port = 1447, guest = true)
            store.save(source, "")
            try {
                val reader = MusicFolderMetadataReader(context, store, testFolderClients())
                for (format in listOf(
                    "m4a",
                    "mp3",
                    "flac",
                )) {
                    assertMetadata(reader.read(FolderAudioRef(source.id, source.revision, "tagged.$format", 0, 0)))
                }
            } finally {
                store.remove(source.id)
            }
        }

    @Test fun metadataUpdatePreservesPlaybackPositionAndDoesNotRebuffer(): Unit =
        runBlocking {
            val host = InstrumentationRegistry.getArguments().getString("smb_fixture_host")
            assumeNotNull(host)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = MusicFolderStore(context)
            val source = MusicFolder(name = "Tags", kind = MusicFolderKind.SMB, host = host!!, share = "Music", port = 1447, guest = true)
            store.save(source, "")
            val manager = io.github.aedev.flow.player.EnhancedMusicPlayerManager
            val oldPlayer = manager.player
            val oldQueue = manager.queueState.value
            val oldTrack = manager.currentTrackState.value
            val track = MusicFolderEntry("tagged.m4a", "tagged.m4a", false).track(source)
            val factory =
                io.github.aedev.flow.player.datasource
                    .MusicFolderDataSourceFactory(
                        context,
                        store,
                        testFolderClients(),
                    ).wrap(
                        androidx.media3.datasource.DefaultDataSource
                            .Factory(context),
                    )
            val player =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    androidx.media3.exoplayer.ExoPlayer
                        .Builder(
                            context,
                        ).setMediaSourceFactory(
                            androidx.media3.exoplayer.source
                                .DefaultMediaSourceFactory(factory),
                        ).build()
                        .apply {
                            volume = 0f
                            setMediaItem(
                                androidx.media3.common.MediaItem
                                    .Builder()
                                    .setMediaId(track.videoId)
                                    .setUri(source.remoteUri("tagged.m4a"))
                                    .build(),
                            )
                            prepare()
                            play()
                        }
                }
            val session =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    androidx.media3.session.MediaSession
                        .Builder(context, player)
                        .setId("folder-metadata-test")
                        .build()
                }
            val future =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    androidx.media3.session.MediaController
                        .Builder(context, session.token)
                        .buildAsync()
                }
            val controller =
                kotlinx.coroutines.withContext(
                    kotlinx.coroutines.Dispatchers.IO,
                ) { future.get(15, java.util.concurrent.TimeUnit.SECONDS) }
            var rebuffered = 0
            try {
                kotlinx.coroutines.withTimeout(15_000) {
                    while (!kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            player.playerError?.let { throw AssertionError("Fixture playback failed", it) }
                            player.currentPosition > 700
                        }
                    ) {
                        kotlinx.coroutines.delay(50)
                    }
                }
                val before =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        manager.javaClass
                            .getDeclaredField("player")
                            .apply { isAccessible = true }
                            .set(null, controller)
                        manager.queueState.value = listOf(track)
                        manager.currentTrackState.value = track
                        player.addListener(
                            object : androidx.media3.common.Player.Listener {
                                override fun onPlaybackStateChanged(state: Int) {
                                    if (state ==
                                        androidx.media3.common.Player.STATE_BUFFERING
                                    ) {
                                        rebuffered++
                                    }
                                }
                            },
                        )
                        player.currentPosition
                    }
                val metadata = MusicFolderMetadata(MusicFolderMetadataReader(context, store, testFolderClients()))
                io.github.aedev.flow.player
                    .MusicFolderPlaybackMetadata(metadata)
                    .enrichCurrent(track)
                kotlinx.coroutines.delay(250)
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    assertEquals("Tagged song", manager.currentTrack.value?.title)
                    assertEquals("Fixture artist", controller.mediaMetadata.artist.toString())
                    assertTrue(player.currentPosition >= before)
                    assertEquals(0, rebuffered)
                }
            } finally {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    manager.javaClass
                        .getDeclaredField("player")
                        .apply { isAccessible = true }
                        .set(null, oldPlayer)
                    manager.queueState.value = oldQueue
                    manager.currentTrackState.value = oldTrack
                    controller.release()
                    session.release()
                    player.release()
                }
                store.remove(source.id)
            }
        }

    @Test fun playsAndSeeksMp3AndFlacFiles(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val directory = File(context.cacheDir, "playlists").apply { mkdirs() }
            for (format in listOf("mp3", "flac")) {
                val file = File(directory, "playback.$format")
                instrumentation.context.assets
                    .open("folders/tagged.$format")
                    .use { input -> file.outputStream().use(input::copyTo) }
                val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
                val player =
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        androidx.media3.exoplayer.ExoPlayer.Builder(context).build().apply {
                            volume = 0f
                            setMediaItem(
                                androidx.media3.common.MediaItem
                                    .fromUri(uri),
                            )
                            prepare()
                            play()
                        }
                    }

                suspend fun await(position: Long) =
                    kotlinx.coroutines.withTimeout(15_000) {
                        while (!kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                player.playerError?.let { throw AssertionError("$format playback failed", it) }
                                player.currentPosition >= position && player.playbackState == androidx.media3.common.Player.STATE_READY
                            }
                        ) {
                            kotlinx.coroutines.delay(50)
                        }
                    }
                try {
                    await(300)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { player.seekTo(2_000) }
                    await(2_000)
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { player.release() }
                    file.delete()
                }
            }
        }

    @Test fun sessionArtworkUsesTheSharedFolderFetcher(): Unit =
        runBlocking {
            val host = InstrumentationRegistry.getArguments().getString("smb_fixture_host")
            assumeNotNull(host)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = MusicFolderStore(context)
            val source = MusicFolder(name = "Art", kind = MusicFolderKind.SMB, host = host!!, share = "Music", port = 1447, guest = true)
            store.save(source, "")
            val original = coil3.SingletonImageLoader.get(context)
            val metadata = MusicFolderMetadata(MusicFolderMetadataReader(context, store, testFolderClients()))
            val loader =
                coil3.ImageLoader
                    .Builder(context)
                    .components { add(FolderArtworkFetcher.Factory(metadata)) }
                    .build()
            coil3.SingletonImageLoader.setUnsafe(loader)
            try {
                val ref = FolderAudioRef(source.id, source.revision, "tagged.m4a", 0, 0)
                val future =
                    io.github.aedev.flow.player
                        .sessionArtworkBitmapLoader(context)
                        .loadBitmap(ref.uri())
                val bitmap =
                    kotlinx.coroutines.withContext(
                        kotlinx.coroutines.Dispatchers.IO,
                    ) { future.get(15, java.util.concurrent.TimeUnit.SECONDS) }
                assertTrue(bitmap.width > 0 && bitmap.height > 0)
            } finally {
                coil3.SingletonImageLoader.setUnsafe(original)
                loader.shutdown()
                store.remove(source.id)
            }
        }

    private fun assertMetadata(metadata: FolderAudioMetadata) {
        assertEquals("Tagged song", metadata.title)
        assertEquals("Fixture artist", metadata.artist)
        assertEquals("Fixture album", metadata.album)
        assertTrue(metadata.duration >= 5)
        assertTrue(metadata.artwork?.isNotEmpty() == true)
    }
}
