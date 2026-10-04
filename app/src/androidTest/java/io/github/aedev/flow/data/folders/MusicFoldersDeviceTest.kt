package io.github.aedev.flow.data.folders

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.data.localmedia.LocalMediaIds
import io.github.aedev.flow.player.datasource.MusicFolderDataSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicFoldersDeviceTest {
    private fun fixture(): MusicFolder {
        val host = InstrumentationRegistry.getArguments().getString("smb_fixture_host")
        assumeNotNull(host)
        return MusicFolder(name = "SMB fixture", kind = MusicFolderKind.SMB, host = host!!, port = 1445, share = "Music", guest = true)
    }

    @Test fun media3PlaysAndSeeksUriBackedLocalContent(): Unit =
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = instrumentation.targetContext
            val directory = java.io.File(context.cacheDir, "playlists").apply { mkdirs() }
            val clip = java.io.File(directory, "local-folder-playback.aac")
            instrumentation.context.assets
                .open("player/silence.aac")
                .use { input -> clip.outputStream().use(input::copyTo) }
            val uri =
                androidx.core.content.FileProvider
                    .getUriForFile(context, context.packageName + ".files", clip)
            val trackId = LocalMediaIds.of(uri)
            val factory =
                MusicFolderDataSourceFactory(
                    context,
                    MusicFolderStore(context),
                    testFolderClients(),
                ).wrap(DefaultDataSource.Factory(context))
            val player =
                withContext(Dispatchers.Main) {
                    ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                        volume = 0f
                        setMediaItem(
                            MediaItem
                                .Builder()
                                .setMediaId(trackId)
                                .setUri(LocalMediaIds.audioUri(trackId))
                                .build(),
                        )
                        prepare()
                        play()
                    }
                }

            suspend fun await(position: Long) =
                withTimeout(20_000) {
                    while (!withContext(Dispatchers.Main) {
                            player.playerError?.let { throw AssertionError("Local content playback failed", it) }
                            player.playbackState == Player.STATE_READY && player.currentPosition >= position
                        }
                    ) {
                        delay(100)
                    }
                }
            try {
                await(100)
                withContext(Dispatchers.Main) { player.seekTo(2_000) }
                await(2_000)
            } finally {
                withContext(Dispatchers.Main) { player.release() }
                clip.delete()
            }
        }

    @Test fun authenticatedShareKeepsSavedPasswordAndSurvivesIdleConnection(): Unit =
        runBlocking {
            val source = fixture().copy(port = 1446, guest = false, username = "milkbeat-fixture")
            val password = "fixture-only-password"
            val client = SmbMusicClient()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = MusicFolderStore(context)
            store.save(source, password)
            try {
                val access = store.access(source.id, source.revision)
                withContext(Dispatchers.IO) { client.test(access.source, access.secrets) }
                val entries = withContext(Dispatchers.IO) { client.list(access.source, access.secrets, "Album") }
                withContext(Dispatchers.IO) {
                    client.open(access.source, access.secrets, entries.first().location).use {
                        val bytes = ByteArray(4)
                        assertEquals(4, it.read(bytes, 0, 0, 4))
                        delay(16_000)
                        assertEquals(4, it.read(bytes, 8, 0, 4))
                        assertEquals("WAVE", String(bytes, Charsets.US_ASCII))
                    }
                }
                store.save(source.copy(name = "Edited fixture"), null)
                val changed = store.folders.first().first { it.id == source.id }
                assertEquals(password, store.access(changed.id, changed.revision).secrets.password)
                assertThrows(java.io.FileNotFoundException::class.java) { runBlocking { store.access(source.id, source.revision) } }
                assertThrows(Exception::class.java) { client.test(source, MusicFolderSecrets("incorrect-password")) }
            } finally {
                store.remove(source.id)
            }
        }

    @Test fun readOnlyShareListsMusicSeeksAndReportsMissingFolders() {
        val source = fixture()
        val client = SmbMusicClient()
        client.test(source, MusicFolderSecrets())
        val roots = client.list(source, MusicFolderSecrets(), "")
        assertEquals(listOf("Album"), roots.map { it.name })
        val files = client.list(source, MusicFolderSecrets(), "Album")
        assertEquals(2, files.size)
        assertFalse(files.any { it.name == "ignore.txt" })
        client.open(source, MusicFolderSecrets(), files.first().location).use {
            assertTrue(it.length > 44)
            val header = ByteArray(4)
            assertEquals(4, it.read(header, 0, 0, 4))
            assertEquals("RIFF", String(header, Charsets.US_ASCII))
            assertEquals(4, it.read(header, 8, 0, 4))
            assertEquals("WAVE", String(header, Charsets.US_ASCII))
        }
        assertThrows(Exception::class.java) { client.test(source.copy(root = "Missing"), MusicFolderSecrets()) }
    }

    @Test fun media3PlaysSeeksAndAdvancesBetweenSmbTracksAndRejectsRemovedSource(): Unit =
        runBlocking {
            val source = fixture()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = MusicFolderStore(context)
            val smb = SmbMusicClient()
            store.save(source, "")
            val files = withContext(Dispatchers.IO) { smb.list(source, MusicFolderSecrets(), "Album").sortedBy { it.name } }
            val factory = MusicFolderDataSourceFactory(context, store, testFolderClients(smb)).wrap(DefaultDataSource.Factory(context))
            val items =
                files.map {
                    val uri = source.remoteUri(it.location)
                    val id = LocalMediaIds.of(uri)
                    assertEquals(uri, LocalMediaIds.audioUri(id))
                    MediaItem
                        .Builder()
                        .setMediaId(id)
                        .setUri(LocalMediaIds.audioUri(id))
                        .build()
                }
            val player =
                withContext(Dispatchers.Main) {
                    ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(factory)).build().apply {
                        volume = 0f
                        setMediaItems(items)
                        prepare()
                        play()
                    }
                }

            suspend fun await(condition: () -> Boolean) =
                withTimeout(25_000) {
                    while (!withContext(Dispatchers.Main) {
                            player.playerError?.let { throw AssertionError("SMB playback failed", it) }
                            condition()
                        }
                    ) {
                        delay(100)
                    }
                }
            try {
                await { player.playbackState == Player.STATE_READY && player.currentPosition > 300 }
                withContext(Dispatchers.Main) { player.seekTo(3_000) }
                await { player.playbackState == Player.STATE_READY && player.currentPosition >= 3_000 }
                await { player.currentMediaItemIndex == 1 && player.currentPosition > 300 }
                withContext(Dispatchers.Main) { player.pause() }
                store.remove(source.id)
                val data = factory.createDataSource()
                try {
                    assertThrows(java.io.IOException::class.java) {
                        data.open(androidx.media3.datasource.DataSpec(items.first().localConfiguration!!.uri))
                    }
                } finally {
                    data.close()
                }
            } finally {
                withContext(Dispatchers.Main) { player.release() }
                store.remove(source.id)
            }
        }
}
