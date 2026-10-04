package io.github.aedev.flow.player.datasource

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import com.google.common.truth.Truth.assertThat
import io.github.aedev.flow.data.folders.RemoteMusicFile
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.EOFException

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class RemoteMusicDataSourceTest {
    private val uri = Uri.parse("smbmusic://source/album/song.wav?revision=1")

    private class BytesFile(
        val bytes: ByteArray,
    ) : RemoteMusicFile {
        var closed = false
        override val length: Long get() = bytes.size.toLong()

        override fun read(
            buffer: ByteArray,
            position: Long,
            offset: Int,
            length: Int,
        ): Int {
            val count = minOf(length, bytes.size - position.toInt())
            if (count <= 0) return -1
            bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        override fun close() {
            closed = true
        }
    }

    @Test fun honorsPositionLengthAndZeroReadsThenCloses() {
        val file = BytesFile(byteArrayOf(1, 2, 3, 4, 5))
        val source = RemoteMusicDataSource { file }
        assertThat(
            source.open(
                DataSpec
                    .Builder()
                    .setUri(uri)
                    .setPosition(1)
                    .setLength(2)
                    .build(),
            ),
        ).isEqualTo(2L)
        val buffer = ByteArray(4)
        assertThat(source.read(buffer, 0, 0)).isEqualTo(0)
        assertThat(source.read(buffer, 1, 4 - 1)).isEqualTo(2)
        assertThat(buffer.toList()).containsExactly(0.toByte(), 2.toByte(), 3.toByte(), 0.toByte()).inOrder()
        assertThat(source.read(buffer, 0, 4)).isEqualTo(C.RESULT_END_OF_INPUT)
        source.close()
        assertThat(file.closed).isTrue()
        assertThat(source.uri).isNull()
    }

    @Test fun seekPastEndFailsAndReleasesHandle() {
        val file = BytesFile(byteArrayOf(1))
        val source = RemoteMusicDataSource { file }
        assertThrows(DataSourceException::class.java) {
            source.open(
                DataSpec
                    .Builder()
                    .setUri(uri)
                    .setPosition(2)
                    .build(),
            )
        }
        assertThat(file.closed).isTrue()
    }

    @Test fun unknownLengthAndSeekingExactlyToEndAreSupported() {
        val source = RemoteMusicDataSource { BytesFile(byteArrayOf(1, 2)) }
        assertThat(
            source.open(
                DataSpec
                    .Builder()
                    .setUri(uri)
                    .setPosition(2)
                    .build(),
            ),
        ).isEqualTo(0L)
        assertThat(source.read(ByteArray(1), 0, 1)).isEqualTo(C.RESULT_END_OF_INPUT)
        source.close()
        assertThat(source.open(DataSpec.Builder().setUri(uri).build())).isEqualTo(2L)
        source.close()
    }

    @Test fun earlyEndIsAnErrorInsteadOfSilentlyTruncatingMusic() {
        val source =
            RemoteMusicDataSource {
                object : RemoteMusicFile {
                    override val length = 10L

                    override fun read(
                        buffer: ByteArray,
                        position: Long,
                        offset: Int,
                        length: Int,
                    ) = -1

                    override fun close() = Unit
                }
            }
        source.open(DataSpec.Builder().setUri(uri).build())
        assertThrows(EOFException::class.java) { source.read(ByteArray(2), 0, 2) }
        source.close()
    }
}
