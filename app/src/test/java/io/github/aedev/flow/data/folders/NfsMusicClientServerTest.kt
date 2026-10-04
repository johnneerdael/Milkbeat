package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Runs only against real servers named by environment variables, for example:
 * NFS_TEST_HOST=192.168.50.23 NFS_TEST_V3_EXPORT=/srv/nfs/music NFS_TEST_V4_EXPORT=/music
 * NFS_TEST_V3_SECURE_EXPORT=/srv/nfs/secure NFS_TEST_V4_SECURE_EXPORT=/secure NFS_TEST_RCLONE_PORT=20490
 * NFS_TEST_FIXTURE=/path/to/the/exported/tree, optionally NFS_TEST_PORT and NFS_TEST_RESTART_CMD.
 */
class NfsMusicClientServerTest {
    private val client = NfsMusicClient()
    private val secrets = MusicFolderSecrets()

    private fun env(name: String): String? = System.getenv(name)?.takeIf(String::isNotBlank)

    private fun folder(
        version: NfsVersion,
        port: Int = env("NFS_TEST_PORT")?.toInt() ?: 2049,
        export: String? = env(if (version == NfsVersion.V3) "NFS_TEST_V3_EXPORT" else "NFS_TEST_V4_EXPORT"),
    ): MusicFolder {
        val host = env("NFS_TEST_HOST")
        assumeTrue(host != null && export != null && env("NFS_TEST_FIXTURE") != null)
        return MusicFolder(name = "NFS", kind = MusicFolderKind.NFS, host = host!!, port = port, share = export!!, nfsVersion = version)
    }

    private val fixture get() = File(checkNotNull(env("NFS_TEST_FIXTURE")))

    private fun verifyTree(source: MusicFolder) {
        assertThat(client.test(source, secrets)).isEqualTo(source)
        val root = client.list(source, secrets, "")
        assertThat(root.map { it.name to it.isDirectory })
            .containsAtLeast("Albums" to true, "Singles" to true, "Many" to true, "big.flac" to false)
        assertThat(root.map { it.name }).containsNoneOf("notes.txt", "cover.jpg", "link.mp3")
        val big = root.single { it.name == "big.flac" }
        assertThat(big.size).isEqualTo(File(fixture, "big.flac").length())
        assertThat(big.modified).isGreaterThan(0L)
        val nested = client.list(source, secrets, "Albums/Café del Mar")
        assertThat(nested.single().location).isEqualTo("Albums/Café del Mar/Ünïcødé – Track.ogg")
        assertThat(client.list(source, secrets, "Many")).hasSize(300)
        assertThat(client.list(source, secrets, "Singles").map { it.name }).containsExactly("Track one.m4a", "Act I: Overture.m4a")
        val deep = (1..14).joinToString("/", prefix = "Deep/")
        assertThat(client.list(source, secrets, deep).map { it.location }).containsExactly("$deep/deep.mp3")
        client.open(source, secrets, "$deep/deep.mp3").use { assertThat(it.length).isEqualTo(File(fixture, "$deep/deep.mp3").length()) }
        assertThat(sha256(source, "big.flac", 32 * 1024)).isEqualTo(sha256(File(fixture, "big.flac").readBytes()))
        assertThat(sha256(source, nested.single().location, 7_001))
            .isEqualTo(sha256(File(fixture, "Albums/Café del Mar/Ünïcødé – Track.ogg").readBytes()))
        client.open(source, secrets, "big.flac").use { file ->
            val expected = File(fixture, "big.flac").readBytes()
            val buffer = ByteArray(100)
            assertThat(file.read(buffer, 1_000_000, 0, 100)).isGreaterThan(0)
            assertThat(buffer.copyOf(10)).isEqualTo(expected.copyOfRange(1_000_000, 1_000_010))
            assertThat(file.read(buffer, file.length, 0, 100)).isEqualTo(-1)
        }
    }

    private fun sha256(
        source: MusicFolder,
        path: String,
        chunk: Int,
    ): String =
        client.open(source, secrets, path).use { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(chunk)
            var position = 0L
            while (position < file.length) {
                val count = file.read(buffer, position, 0, minOf(chunk.toLong(), file.length - position).toInt())
                assertThat(count).isGreaterThan(0)
                digest.update(buffer, 0, count)
                position += count
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun v3WithPortmapperListsAndReads() = verifyTree(folder(NfsVersion.V3))

    @Test fun v40ListsAndReads() = verifyTree(folder(NfsVersion.V4))

    @Test fun v41ListsAndReads() = verifyTree(folder(NfsVersion.V4_1))

    @Test fun autoListsAndReads() = verifyTree(folder(NfsVersion.AUTO))

    @Test fun v4PseudoRootExportResolvesTheConfiguredRoot() {
        val export = env("NFS_TEST_V4_EXPORT")
        assumeTrue(export != null)
        val source = folder(NfsVersion.V4_1, export = "/").copy(root = export!!)
        assertThat(client.list(source, secrets, "Albums").map { it.name }).containsExactly("Abbey Road", "Café del Mar")
    }

    @Test fun v3WithoutPortmapperUsesTheConfiguredPort() {
        val port = env("NFS_TEST_RCLONE_PORT")?.toInt()
        assumeTrue(port != null)
        verifyTree(folder(NfsVersion.V3, port = port!!, export = "/"))
        verifyTree(folder(NfsVersion.AUTO, port = port, export = "/"))
    }

    @Test fun secureExportReportsThePrivilegedPortRequirement() {
        for (version in NfsVersion.entries) {
            val export = env(if (version == NfsVersion.V3) "NFS_TEST_V3_SECURE_EXPORT" else "NFS_TEST_V4_SECURE_EXPORT") ?: continue
            val error =
                assertThrows(version.name, NfsInsecurePortRequiredException::class.java) {
                    client.list(folder(version, export = export), secrets, "")
                }
            println("$version secure export: ${error.message}")
        }
    }

    @Test fun missingPathsFailWithoutFallingBack() {
        val source = folder(NfsVersion.AUTO)
        val error = assertThrows(java.io.IOException::class.java) { client.list(source, secrets, "Nope") }
        assertThat(error).isNotInstanceOf(NfsVersionUnsupportedException::class.java)
    }

    @Test fun repeatedOpenAndCloseLeavesNoThreads() {
        val folders = listOf(NfsVersion.V3, NfsVersion.V4, NfsVersion.V4_1).map(::folder)
        val before = nfsThreads()
        var peak = 0
        repeat(10) {
            for (source in folders) {
                client.open(source, secrets, "big.flac").use { file ->
                    file.read(ByteArray(4096), 0, 0, 4096)
                    peak = maxOf(peak, nfsThreads().size)
                }
                client.list(source, secrets, "Albums")
            }
        }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (nfsThreads().size > before.size && System.nanoTime() < deadline) Thread.sleep(50)
        println("NFS threads before=${before.size} peak=$peak after=${nfsThreads()}")
        assertThat(nfsThreads().size).isEqualTo(before.size)
        assertThat(peak).isAtMost(3)
    }

    @Test fun openFileReconnectsAfterTheServerRestarts() {
        val restart = env("NFS_TEST_RESTART_CMD")
        assumeTrue(restart != null && env("NFS_TEST_V3_EXPORT") != null)
        for (version in listOf(NfsVersion.V3, NfsVersion.V4, NfsVersion.V4_1)) {
            val source = folder(version)
            val expected = File(fixture, "big.flac").readBytes()
            client.open(source, secrets, "big.flac").use { file ->
                val buffer = ByteArray(4096)
                assertThat(file.read(buffer, 0, 0, buffer.size)).isGreaterThan(0)
                val process = ProcessBuilder("/bin/sh", "-c", restart).inheritIO().start()
                assertThat(process.waitFor()).isEqualTo(0)
                val count = file.read(buffer, 2_000_000, 0, buffer.size)
                assertThat(count).isGreaterThan(0)
                assertThat(buffer.copyOf(count)).isEqualTo(expected.copyOfRange(2_000_000, 2_000_000 + count))
            }
        }
    }

    private fun nfsThreads(): List<String> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.isAlive }
            .map { it.name }
            .filter { "milkbeat-nfs" in it || "ReplyQueue" in it || "Grizzly" in it }
}
