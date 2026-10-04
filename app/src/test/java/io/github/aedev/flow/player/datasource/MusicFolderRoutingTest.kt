package io.github.aedev.flow.player.datasource

import android.net.Uri
import androidx.media3.datasource.ByteArrayDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.folders.RemoteMusicFile
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MusicFolderRoutingTest {
    @Test fun smbBypassesPluginAndCacheWhileAllOtherSchemesDelegate() {
        var delegated = 0
        var openedRemote = 0
        val source =
            MusicFolderRoutingDataSource(
                DataSource.Factory {
                    delegated++
                    ByteArrayDataSource(byteArrayOf(1))
                },
                remote = {
                    openedRemote++
                    ByteArrayDataSource(byteArrayOf(2))
                },
            )
        val bytes = ByteArray(1)
        for ((uri, expected) in listOf(
            "smbmusic://source/song.wav" to 2,
            "music://track" to 1,
            "https://audio.test/track" to 1,
            "content://music/track" to 1,
            "file:///track" to 1,
        )) {
            source.open(DataSpec(Uri.parse(uri)))
            source.read(bytes, 0, 1)
            assertThat(bytes.single().toInt()).isEqualTo(expected)
            source.close()
        }
        assertThat(openedRemote).isEqualTo(1)
        assertThat(delegated).isEqualTo(4)
    }

    @Test fun transferEventsAreBalancedAndOpenFailureDoesNotSignalStart() {
        val events = mutableListOf<String>()
        val source =
            RemoteMusicDataSource {
                object : RemoteMusicFile {
                    override val length = 1L

                    override fun read(
                        buffer: ByteArray,
                        position: Long,
                        offset: Int,
                        length: Int,
                    ): Int {
                        buffer[offset] = 1
                        return 1
                    }

                    override fun close() = Unit
                }
            }
        source.addTransferListener(
            object : TransferListener {
                override fun onTransferInitializing(
                    source: DataSource,
                    spec: DataSpec,
                    network: Boolean,
                ) {
                    events += "initializing"
                }

                override fun onTransferStart(
                    source: DataSource,
                    spec: DataSpec,
                    network: Boolean,
                ) {
                    events += "start"
                }

                override fun onBytesTransferred(
                    source: DataSource,
                    spec: DataSpec,
                    network: Boolean,
                    bytes: Int,
                ) {
                    events += "bytes:$bytes"
                }

                override fun onTransferEnd(
                    source: DataSource,
                    spec: DataSpec,
                    network: Boolean,
                ) {
                    events += "end"
                }
            },
        )
        source.open(DataSpec(Uri.parse("smbmusic://source/track")))
        source.read(ByteArray(1), 0, 1)
        source.close()
        source.close()
        assertThat(events).containsExactly("initializing", "start", "bytes:1", "end").inOrder()
    }

    @Test fun documentTreesReadCurrentFileBytesWithoutConsultingPluginCache() {
        var cached = 0
        var documents = 0
        val source =
            MusicFolderRoutingDataSource(
                DataSource.Factory {
                    cached++
                    ByteArrayDataSource(byteArrayOf(1))
                },
                { ByteArrayDataSource(byteArrayOf(2)) },
                documents = {
                    documents++
                    ByteArrayDataSource(byteArrayOf(3))
                },
            )
        source.open(DataSpec(Uri.parse("content://documents/tree/root/document/root%3Asong")))
        val bytes = ByteArray(1)
        source.read(bytes, 0, 1)
        source.close()
        assertThat(bytes.single().toInt()).isEqualTo(3)
        assertThat(cached).isEqualTo(0)
        assertThat(documents).isEqualTo(1)
    }
}
