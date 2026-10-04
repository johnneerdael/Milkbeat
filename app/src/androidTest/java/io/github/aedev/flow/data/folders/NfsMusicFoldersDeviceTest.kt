package io.github.aedev.flow.data.folders

import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.aedev.flow.player.datasource.RemoteMusicDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.dcache.oncrpc4j.rpc.RpcAuthTypeUnix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * Runs against real servers only, named by instrumentation arguments: nfsHost plus nfsV3Export and/or
 * nfsV4Export (optionally nfsPort, nfsRclonePort, nfsV3SecureExport, nfsV4SecureExport) and nfsBigSha256, the
 * SHA-256 of `big.flac` at the export root.
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class NfsMusicFoldersDeviceTest {
    private val arguments = InstrumentationRegistry.getArguments()
    private val client = NfsMusicClient()
    private val secrets = MusicFolderSecrets()

    private fun argument(name: String): String? = arguments.getString(name)?.takeIf(String::isNotBlank)

    private fun folder(
        version: NfsVersion,
        export: String? = argument(if (version == NfsVersion.V3) "nfsV3Export" else "nfsV4Export"),
        port: Int = argument("nfsPort")?.toInt() ?: 2049,
    ): MusicFolder {
        val host = argument("nfsHost")
        assumeTrue(host != null && export != null && argument("nfsBigSha256") != null)
        return MusicFolder(name = "NFS", kind = MusicFolderKind.NFS, host = host!!, port = port, share = export!!, nfsVersion = version)
    }

    private fun verify(source: MusicFolder) {
        client.test(source, secrets)
        val root = client.list(source, secrets, "")
        assertTrue(root.any { it.name == "Albums" && it.isDirectory })
        assertTrue(root.none { it.name == "notes.txt" || it.name == "link.mp3" })
        val nested = client.list(source, secrets, "Albums/Café del Mar")
        assertEquals(listOf("Albums/Café del Mar/Ünïcødé – Track.ogg"), nested.map { it.location })
        assertEquals(300, client.list(source, secrets, "Many").size)
        val digest = MessageDigest.getInstance("SHA-256")
        client.open(source, secrets, "big.flac").use { file ->
            val buffer = ByteArray(32 * 1024)
            var position = 0L
            while (position < file.length) {
                val count = file.read(buffer, position, 0, minOf(buffer.size.toLong(), file.length - position).toInt())
                assertTrue(count > 0)
                digest.update(buffer, 0, count)
                position += count
            }
            assertEquals(-1, file.read(buffer, file.length, 0, buffer.size))
        }
        assertEquals(argument("nfsBigSha256"), digest.digest().joinToString("") { "%02x".format(it) })
        assertEquals("Come Together", title(source, "Albums/Abbey Road/01 Come Together.mp3"))
    }

    private fun title(
        source: MusicFolder,
        path: String,
    ): String? =
        client.open(source, secrets, path).use { file ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(
                    object : MediaDataSource() {
                        override fun readAt(
                            position: Long,
                            buffer: ByteArray,
                            offset: Int,
                            size: Int,
                        ): Int = file.read(buffer, position, offset, size)

                        override fun getSize(): Long = file.length

                        override fun close() = Unit
                    },
                )
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            } finally {
                retriever.release()
            }
        }

    @Test fun v3ListsReadsAndExtractsTags() = verify(folder(NfsVersion.V3))

    @Test fun v40ListsReadsAndExtractsTags() = verify(folder(NfsVersion.V4))

    @Test fun v41ListsReadsAndExtractsTags() = verify(folder(NfsVersion.V4_1))

    @Test fun autoListsReadsAndExtractsTags() = verify(folder(NfsVersion.AUTO))

    @Test fun v3WithoutPortmapperUsesTheConfiguredPort() {
        val port = argument("nfsRclonePort")?.toInt()
        assumeTrue(port != null)
        verify(folder(NfsVersion.AUTO, export = "/", port = port!!))
    }

    @Test fun secureExportReportsThePrivilegedPortRequirement() {
        val v3 = argument("nfsV3SecureExport")
        val v4 = argument("nfsV4SecureExport")
        assumeTrue(v3 != null || v4 != null)
        v3?.let { export ->
            assertThrows(NfsInsecurePortRequiredException::class.java) { client.list(folder(NfsVersion.V3, export), secrets, "") }
        }
        v4?.let { export ->
            assertThrows(NfsInsecurePortRequiredException::class.java) { client.list(folder(NfsVersion.AUTO, export), secrets, "") }
        }
    }

    @Test fun exoPlayerPlaysAndSeeksAcrossAnIdlePause(): Unit =
        runBlocking {
            val source = folder(NfsVersion.AUTO)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val factory = DataSource.Factory { RemoteMusicDataSource { client.open(source, secrets, "big.flac") } }
            val player =
                withContext(Dispatchers.Main) {
                    ExoPlayer.Builder(context).setMediaSourceFactory(ProgressiveMediaSource.Factory(factory)).build().apply {
                        volume = 0f
                        setMediaItem(MediaItem.fromUri("nfsmusic://fixture/big.flac"))
                        prepare()
                        play()
                    }
                }

            suspend fun await(position: Long) =
                withTimeout(30_000) {
                    while (!withContext(Dispatchers.Main) {
                            player.playerError?.let { throw AssertionError("NFS playback failed", it) }
                            player.playbackState == Player.STATE_READY && player.isPlaying && player.currentPosition >= position
                        }
                    ) {
                        delay(100)
                    }
                }
            try {
                await(500)
                withContext(Dispatchers.Main) { player.pause() }
                delay(20_000)
                withContext(Dispatchers.Main) {
                    player.seekTo(30_000)
                    player.play()
                }
                await(31_000)
            } finally {
                withContext(Dispatchers.Main) { player.release() }
            }
        }

    @Test fun oncrpc4jUnixCredentialsCannotBeBuiltOnAndroid() {
        assertThrows(NoClassDefFoundError::class.java) { RpcAuthTypeUnix(0, 0, intArrayOf(0), 0, "milkbeat") }
    }
}
