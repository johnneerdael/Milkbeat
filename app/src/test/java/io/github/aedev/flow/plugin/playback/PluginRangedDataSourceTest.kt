package io.github.aedev.flow.plugin.playback

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PluginRangedDataSourceTest {
    private val file = Random(3).nextBytes(10_000)
    private val uri = Uri.parse("https://media.example/song.mp3")

    /** A range-serving HTTP server that refuses any request longer than [limit]. */
    private inner class RangeServer(
        private val limit: Long? = null,
        private val reportSize: Boolean = true,
    ) : DataSource {
        val opens = mutableListOf<Pair<Long, Long>>()
        private var at = 0
        private var stop = 0
        private var headers = emptyMap<String, List<String>>()

        override fun addTransferListener(transferListener: TransferListener) = Unit

        override fun open(dataSpec: DataSpec): Long {
            opens += dataSpec.position to dataSpec.length
            if (limit != null && (dataSpec.length == C.LENGTH_UNSET.toLong() || dataSpec.length > limit)) {
                throw HttpDataSource.InvalidResponseCodeException(
                    403,
                    null,
                    null,
                    emptyMap(),
                    dataSpec,
                    ByteArray(0),
                )
            }
            if (dataSpec.position >= file.size) {
                throw HttpDataSource.InvalidResponseCodeException(416, null, null, emptyMap(), dataSpec, ByteArray(0))
            }
            at = dataSpec.position.toInt()
            stop = if (dataSpec.length == C.LENGTH_UNSET.toLong()) file.size else minOf(file.size, at + dataSpec.length.toInt())
            headers = if (reportSize) mapOf("content-range" to listOf("bytes $at-${stop - 1}/${file.size}")) else emptyMap()
            return (stop - at).toLong()
        }

        override fun read(
            buffer: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (at >= stop) return C.RESULT_END_OF_INPUT
            val count = minOf(length, stop - at, 700)
            System.arraycopy(file, at, buffer, offset, count)
            at += count
            return count
        }

        override fun getUri(): Uri = uri

        override fun getResponseHeaders(): Map<String, List<String>> = headers

        override fun close() = Unit
    }

    private fun readAll(
        source: DataSource,
        spec: DataSpec,
    ): Pair<Long, ByteArray> {
        val length = source.open(spec)
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(1_024)
        while (true) {
            val read = source.read(buffer, 0, buffer.size)
            if (read == C.RESULT_END_OF_INPUT) break
            out.write(buffer, 0, read)
        }
        source.close()
        return length to out.toByteArray()
    }

    private fun spec(
        position: Long = 0,
        length: Long = C.LENGTH_UNSET.toLong(),
        maxBytes: Long? = 4_096,
    ) = DataSpec
        .Builder()
        .setUri(uri)
        .setPosition(position)
        .setLength(length)
        .build()
        .withRangePolicy(maxBytes?.let(PluginRangePolicy::Declared))

    @Test
    fun `one open reads the whole file through ranges no larger than the declared size`() {
        val server = RangeServer(limit = 4_096)
        val (length, bytes) = readAll(PluginRangedDataSource(server), spec())
        assertThat(length).isEqualTo(file.size.toLong())
        assertThat(bytes).isEqualTo(file)
        assertThat(server.opens).containsExactly(0L to 4_096L, 4_096L to 4_096L, 8_192L to 1_808L).inOrder()
    }

    @Test
    fun `a bounded or offset open stays within its own span`() {
        val server = RangeServer(limit = 4_096)
        val (length, bytes) = readAll(PluginRangedDataSource(server), spec(position = 1_000, length = 5_000))
        assertThat(length).isEqualTo(5_000)
        assertThat(bytes).isEqualTo(file.copyOfRange(1_000, 6_000))
        assertThat(server.opens).containsExactly(1_000L to 4_096L, 5_096L to 904L).inOrder()
    }

    @Test
    fun `without a reported size it stops at a short range or the server's end`() {
        val (length, bytes) = readAll(PluginRangedDataSource(RangeServer(reportSize = false)), spec(maxBytes = 5_000))
        assertThat(length).isEqualTo(C.LENGTH_UNSET.toLong())
        assertThat(bytes).isEqualTo(file)
        val exact = RangeServer(reportSize = false)
        assertThat(readAll(PluginRangedDataSource(exact), spec(maxBytes = 2_500)).second).isEqualTo(file)
        assertThat(exact.opens.last()).isEqualTo(10_000L to 2_500L)
    }

    @Test
    fun `requests without a declared size pass through unchanged`() {
        val server = RangeServer()
        val (length, bytes) = readAll(PluginRangedDataSource(server), spec(maxBytes = null))
        assertThat(length).isEqualTo(file.size.toLong())
        assertThat(bytes).isEqualTo(file)
        assertThat(server.opens).containsExactly(0L to C.LENGTH_UNSET.toLong())
    }

    @Test(expected = DataSourceException::class)
    fun `a refused first range is the caller's error`() {
        PluginRangedDataSource(RangeServer(limit = 1_000)).open(spec(maxBytes = 4_096))
    }
}
