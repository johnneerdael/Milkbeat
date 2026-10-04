package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/** Runs against a real server named by MILKBEAT_WEBDAV_URL, MILKBEAT_WEBDAV_USER and MILKBEAT_WEBDAV_PASSWORD. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class WebDavIntegrationTest {
    private val url = System.getenv("MILKBEAT_WEBDAV_URL")
    private val user = System.getenv("MILKBEAT_WEBDAV_USER").orEmpty()
    private val password = System.getenv("MILKBEAT_WEBDAV_PASSWORD").orEmpty()

    private fun walk(
        client: WebDavMusicClient,
        folder: MusicFolder,
        path: String,
        out: MutableList<MusicFolderEntry>,
    ) {
        for (entry in client.list(folder, MusicFolderSecrets(password), path)) {
            println("WEBDAV ${if (entry.isDirectory) "dir " else "file"} ${entry.location} size=${entry.size} modified=${entry.modified}")
            if (entry.isDirectory) walk(client, folder, entry.location, out) else out += entry
        }
    }

    @Test fun listTestAndRangedReadsAgainstARealServer() {
        assumeTrue(url != null)
        val folder = davFolder(url, guest = user.isEmpty(), username = user)
        val client = davClient()
        assertThat(client.test(folder, MusicFolderSecrets(password))).isSameInstanceAs(folder)
        val files = mutableListOf<MusicFolderEntry>()
        walk(client, folder, "", files)
        assertThat(files).isNotEmpty()
        for (entry in files) {
            val whole = MessageDigest.getInstance("SHA-256")
            val expected = MessageDigest.getInstance("SHA-256")
            val fullUrl = client.stream(folder, MusicFolderSecrets(password), entry.location)
            val request =
                okhttp3.Request
                    .Builder()
                    .url(fullUrl.url)
                    .apply { fullUrl.headers.forEach { (k, v) -> header(k, v) } }
                    .build()
            val bytes =
                client.httpClient
                    .newCall(request)
                    .execute()
                    .use { it.body.bytes() }
            expected.update(bytes)
            client.open(folder, MusicFolderSecrets(password), entry.location).use { file ->
                assertThat(file.length).isEqualTo(bytes.size.toLong())
                val buffer = ByteArray(32 * 1024)
                var position = 0L
                while (true) {
                    val read = file.read(buffer, position, 0, buffer.size)
                    if (read < 0) break
                    whole.update(buffer, 0, read)
                    position += read
                }
                val tailStart = maxOf(0, bytes.size - 64 * 1024)
                val tail = ByteArray(bytes.size - tailStart)
                var filled = 0
                while (filled < tail.size) {
                    val read = file.read(tail, tailStart + filled.toLong(), filled, tail.size - filled)
                    if (read < 0) break
                    filled += read
                }
                assertThat(tail).isEqualTo(bytes.copyOfRange(tailStart, bytes.size))
                val head = ByteArray(16)
                assertThat(file.read(head, 0, 0, 16)).isGreaterThan(0)
                assertThat(head.copyOf(minOf(16, bytes.size))).isEqualTo(bytes.copyOf(minOf(16, bytes.size)))
            }
            val digest = whole.digest()
            assertThat(digest).isEqualTo(expected.digest())
            println("WEBDAV verified ${entry.location} bytes=${bytes.size} sha256=${digest.joinToString("") { "%02x".format(it) }}")
        }
    }
}
