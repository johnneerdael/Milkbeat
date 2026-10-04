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
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Plays a folder through the production routing for each network protocol. Needs `remote_fixture_host` and servers
 * started with: `rclone serve webdav <tree> --addr :18090 --user milk --pass 'p@ss:ö'`,
 * `rclone serve sftp <tree> --addr :12230 --user milk --pass secret` and `rclone serve nfs <tree> --addr :12049`,
 * where `<tree>/Café Album` holds `01 Été #1.mp3` (title "Été Song", 90 s) and `02 Second.flac`, and
 * `<tree>/Live: 2024` holds `Act I: Overture.mp3`.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RemoteFolderPlaybackDeviceTest {
    private fun host(): String {
        val host = InstrumentationRegistry.getArguments().getString("remote_fixture_host")
        assumeNotNull(host)
        return host!!
    }

    @Test fun webDavFolderPlaysSeeksAndAdvances() =
        playFolder(
            MusicFolder(name = "WebDAV fixture", kind = MusicFolderKind.WEBDAV, url = "http://${host()}:18090/", username = "milk"),
            "p@ss:ö",
        )

    @Test fun sftpFolderPinsTheServerKeyThenPlaysSeeksAndAdvances() =
        playFolder(
            MusicFolder(name = "SFTP fixture", kind = MusicFolderKind.SFTP, host = host(), port = 12230, username = "milk"),
            "secret",
        )

    @Test fun nfsFolderPlaysSeeksAndAdvances() =
        playFolder(MusicFolder(name = "NFS fixture", kind = MusicFolderKind.NFS, host = host(), port = 12049, share = "/"), "")

    private fun playFolder(
        configured: MusicFolder,
        password: String,
    ): Unit =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val store = MusicFolderStore(context)
            val clients = RemoteMusicClients(SmbMusicClient(), WebDavMusicClient(OkHttpClient()), SftpMusicClient(), NfsMusicClient())
            val repository = MusicFolderRepository(store, DocumentMusicFolders(context), clients)
            repository.save(configured, password, "")
            val source = store.folders.first().single { it.id == configured.id }
            try {
                if (source.kind == MusicFolderKind.SFTP) assertTrue(source.hostKey.startsWith("ssh-"))
                assertTrue(repository.list(source, "").any { it.isDirectory && it.name == "Café Album" })
                assertEquals(listOf("Live: 2024/Act I: Overture.mp3"), repository.list(source, "Live: 2024").map { it.location })
                val files = repository.list(source, "Café Album")
                assertEquals(listOf("01 Été #1.mp3", "02 Second.flac"), files.map { it.name })
                val tagged = MusicFolderMetadata(MusicFolderMetadataReader(context, store, clients)).enrich(files.first().track(source))
                assertEquals("Été Song", tagged.title)
                val factory = MusicFolderDataSourceFactory(context, store, clients).wrap(DefaultDataSource.Factory(context))
                val items =
                    files.map {
                        val id = LocalMediaIds.of(source.remoteUri(it.location))
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
                    withTimeout(30_000) {
                        while (!withContext(Dispatchers.Main) {
                                player.playerError?.let { throw AssertionError("${source.kind} playback failed", it) }
                                condition()
                            }
                        ) {
                            delay(100)
                        }
                    }
                try {
                    await { player.playbackState == Player.STATE_READY && player.currentPosition > 300 }
                    withContext(Dispatchers.Main) { player.seekTo(45_000) }
                    await { player.playbackState == Player.STATE_READY && player.currentPosition >= 45_000 }
                    withContext(Dispatchers.Main) { player.seekTo(88_500) }
                    await { player.currentMediaItemIndex == 1 && player.currentPosition > 300 }
                } finally {
                    withContext(Dispatchers.Main) { player.release() }
                }
            } finally {
                store.remove(configured.id)
            }
        }
}
