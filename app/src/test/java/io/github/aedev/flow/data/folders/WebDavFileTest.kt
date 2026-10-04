package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WebDavFileTest {
    private val data = Random(7).nextBytes(300_000)
    private var dav: DavFileServer? = null

    @After fun tearDown() {
        dav?.shutdown()
    }

    private fun open(
        honorRange: Boolean = true,
        configure: DavFileServer.() -> Unit = {},
    ): RemoteMusicFile {
        val server = DavFileServer(honorRange).start().also { dav = it }
        server.files["/dav/Music/a%20b.flac"] = data
        server.configure()
        return davClient().open(davFolder(server.server.url("/dav/Music").toString()), MusicFolderSecrets(), "a b.flac")
    }

    private fun RemoteMusicFile.readAll(
        from: Long,
        count: Int,
        chunk: Int = 32 * 1024,
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(chunk)
        var position = from
        while (out.size() < count) {
            val read = read(buffer, position, 0, minOf(chunk, count - out.size()))
            if (read < 0) break
            out.write(buffer, 0, read)
            position += read
        }
        return out.toByteArray()
    }

    @Test fun lengthComesFromPropfind() {
        open().use { assertThat(it.length).isEqualTo(data.size.toLong()) }
        assertThat(dav!!.requests.map { it.method }).containsExactly("PROPFIND")
    }

    @Test fun lengthFallsBackToHead() {
        open { headLengthOnly = true }.use { assertThat(it.length).isEqualTo(data.size.toLong()) }
        assertThat(dav!!.requests.map { it.method }).containsExactly("PROPFIND", "HEAD").inOrder()
    }

    @Test fun sequentialReadsContinueOneResponse() {
        open().use { file ->
            val read = file.readAll(0, 60_000, chunk = 4096)
            assertThat(read).isEqualTo(data.copyOfRange(0, 60_000))
        }
        assertThat(dav!!.rangeRequests()).hasSize(1)
    }

    @Test fun sequentialReadsAcrossAWindowContinueWithALargerRange() {
        open().use { file ->
            assertThat(file.readAll(0, data.size)).isEqualTo(data)
        }
        assertThat(dav!!.rangeRequests()).containsExactly("bytes=0-65535", "bytes=65536-${data.size - 1}").inOrder()
    }

    @Test fun jumpingReissuesARangeRequest() {
        open().use { file ->
            assertThat(file.readAll(0, 100)).isEqualTo(data.copyOfRange(0, 100))
            assertThat(file.readAll(250_000, 100)).isEqualTo(data.copyOfRange(250_000, 250_100))
            assertThat(file.readAll(10, 100)).isEqualTo(data.copyOfRange(10, 110))
        }
        assertThat(dav!!.rangeRequests()).hasSize(3)
    }

    @Test fun lastBytesAndEndOfFile() {
        open().use { file ->
            val tail = data.size - 1000
            assertThat(file.readAll(tail.toLong(), 5000)).isEqualTo(data.copyOfRange(tail, data.size))
            assertThat(file.read(ByteArray(10), data.size.toLong(), 0, 10)).isEqualTo(-1)
            assertThat(file.read(ByteArray(10), data.size + 5L, 0, 10)).isEqualTo(-1)
        }
    }

    @Test fun serverIgnoringRangeIsSkippedToPosition() {
        open(honorRange = false).use { file ->
            assertThat(file.readAll(1234, 5000)).isEqualTo(data.copyOfRange(1234, 6234))
            assertThat(file.readAll(6234, 5000)).isEqualTo(data.copyOfRange(6234, 11234))
            assertThat(file.readAll(100, 10)).isEqualTo(data.copyOfRange(100, 110))
        }
        assertThat(dav!!.rangeRequests()).hasSize(2)
    }

    @Test fun rangeNotSatisfiableMeansEndOfFile() {
        open { files["/dav/Music/a%20b.flac"] = data }.use { file ->
            dav!!.files["/dav/Music/a%20b.flac"] = ByteArray(10)
            assertThat(file.read(ByteArray(10), 100_000, 0, 10)).isEqualTo(-1)
        }
    }

    @Test fun errorStatusesAreIOExceptions() {
        open().use { file ->
            dav!!.files.clear()
            val failure = runCatching { file.read(ByteArray(10), 0, 0, 10) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(FileNotFoundException::class.java)
        }
        val missing =
            runCatching { davClient().open(davFolder(dav!!.server.url("/dav/Music").toString()), MusicFolderSecrets(), "none.mp3") }
        assertThat(missing.exceptionOrNull()).isInstanceOf(IOException::class.java)
    }

    @Test fun closeIsIdempotentAndReleasesTheResponse() {
        val file = open()
        file.readAll(0, 10)
        file.close()
        file.close()
    }
}
