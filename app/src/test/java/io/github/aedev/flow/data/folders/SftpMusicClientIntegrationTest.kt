package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Properties

/**
 * Runs against a real SFTP server and is skipped unless `SFTP_TEST_DIR` points at a fixture directory holding
 * `params.properties`, `keys/` and a local copy of the served `music/` tree.
 */
class SftpMusicClientIntegrationTest {
    private val client = SftpMusicClient()
    private lateinit var dir: File
    private lateinit var params: Properties

    @Before fun requireServer() {
        val path = System.getenv("SFTP_TEST_DIR")
        assumeTrue("SFTP_TEST_DIR not set", path != null)
        dir = File(path!!)
        params = Properties().apply { File(dir, "params.properties").inputStream().use(::load) }
    }

    private fun folder(
        root: String = params.getProperty("root"),
        hostKey: String = "",
        keyAuth: Boolean = false,
    ) = MusicFolder(
        name = "sftp",
        kind = MusicFolderKind.SFTP,
        host = System.getenv("SFTP_TEST_HOST") ?: params.getProperty("host"),
        port = params.getProperty("port").toInt(),
        root = root,
        username = params.getProperty("user"),
        hostKey = hostKey,
        keyAuth = keyAuth,
    )

    private fun symlinks() = params.getProperty("symlinks", "true").toBoolean()

    private fun password() = MusicFolderSecrets(password = params.getProperty("password"))

    private fun hostKeys(): Map<String, String> =
        File(dir, "hostkeys.txt").readLines().filter(String::isNotBlank).associate { it.substringBefore(' ') to it }

    private fun keySecrets(
        name: String,
        passphrase: String = "",
    ) = MusicFolderSecrets(password = passphrase, privateKey = File(dir, "keys/$name").readText())

    private fun trusted(
        root: String = params.getProperty("root"),
        keyAuth: Boolean = false,
        secrets: MusicFolderSecrets = password(),
    ): MusicFolder = client.test(folder(root = root, keyAuth = keyAuth), secrets)

    @Test fun trustOnFirstUseReturnsTheFingerprintOfAServerKey() {
        val saved = client.test(folder(), password())
        assertThat(hostKeys().values).contains(saved.hostKey)
    }

    @Test fun everyPinnedKeyTypeIsNegotiatedAndVerified() {
        for ((type, fingerprint) in hostKeys()) {
            val saved = client.test(folder(hostKey = fingerprint), password())
            assertThat(saved.hostKey).isEqualTo(fingerprint)
            assertThat(type).isNotEmpty()
        }
    }

    @Test fun aWrongPinnedKeyIsRejected() {
        val genuine = hostKeys().values.first()
        val forged = genuine.dropLast(2) + if (genuine.endsWith("A")) "BB" else "AA"
        assertThrows(IOException::class.java) { client.test(folder(hostKey = forged), password()) }
        assertThrows(IOException::class.java) { client.list(folder(hostKey = forged), password(), "") }
        assertThrows(IOException::class.java) { client.open(folder(hostKey = forged), password(), "root track.opus") }
    }

    @Test fun aWrongPasswordIsRejected() {
        assertThrows(IOException::class.java) { client.test(folder(), MusicFolderSecrets(password = "nope")) }
    }

    @Test fun listAndOpenRefuseAnUnpinnedFolder() {
        assertThrows(IllegalArgumentException::class.java) { client.list(folder(), password(), "") }
        assertThrows(IllegalArgumentException::class.java) { client.open(folder(), password(), "root track.opus") }
    }

    @Test fun listsMusicAndFoldersSkippingBrokenAndUnrepresentableEntries() {
        val source = trusted()
        val entries = client.list(source, password(), "").associateBy { it.name }
        assertThat(entries.keys).containsAtLeast("Big Noise.wav", "Café del Mar", "Empty Folder", "Spaced Out Album", "root track.opus")
        assertThat(entries.keys).containsNoneOf("broken.mp3", "Track: colon.mp3", "readme.txt")
        assertThat(entries.getValue("Café del Mar").isDirectory).isTrue()
        if (symlinks()) assertThat(entries.getValue("LinkedAlbum").isDirectory).isTrue()
        val big = entries.getValue("Big Noise.wav")
        assertThat(big.isDirectory).isFalse()
        assertThat(big.size).isEqualTo(File(dir, "music/Big Noise.wav").length())
        assertThat(big.modified).isGreaterThan(0L)
    }

    @Test fun listsNestedUnicodeAndSymlinkedFolders() {
        val source = trusted()
        val nested = client.list(source, password(), "Café del Mar/Sébastien – Ünïcode")
        assertThat(nested.map { it.name }).containsExactly("01 Tröck één.flac")
        assertThat(nested.single().location).isEqualTo("Café del Mar/Sébastien – Ünïcode/01 Tröck één.flac")
        if (symlinks()) assertThat(client.list(source, password(), "LinkedAlbum").map { it.name }).containsExactly("Disc 1")
        assertThat(client.list(source, password(), "Spaced Out Album/Disc 1").map { it.name }).containsExactly("02 Second track.mp3")
        assertThat(client.list(source, password(), "Empty Folder")).isEmpty()
    }

    @Test fun absoluteAndLoginRelativeRootsBothResolve() {
        val relative = trusted(root = params.getProperty("root"))
        val absolute = trusted(root = params.getProperty("absoluteRoot"))
        assertThat(client.list(absolute, password(), "").map { it.name }).isEqualTo(client.list(relative, password(), "").map { it.name })
    }

    @Test fun aRootThatIsAFileOrMissingFailsTheTest() {
        assertThrows(IOException::class.java) { client.test(folder(root = "${params.getProperty("root")}/Big Noise.wav"), password()) }
        assertThrows(IOException::class.java) { client.test(folder(root = "does/not/exist"), password()) }
    }

    @Test fun sequentialAndSeekingReadsMatchTheOriginalBytes() {
        val source = trusted()
        val original = File(dir, "music/Big Noise.wav").readBytes()
        client.open(source, password(), "Big Noise.wav").use { file ->
            assertThat(file.length).isEqualTo(original.size.toLong())
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(32 * 1024)
            var position = 0L
            while (true) {
                val count = file.read(buffer, position, 0, buffer.size)
                if (count == -1) break
                assertThat(count).isGreaterThan(0)
                digest.update(buffer, 0, count)
                position += count
            }
            assertThat(position).isEqualTo(original.size.toLong())
            assertThat(digest.digest()).isEqualTo(MessageDigest.getInstance("SHA-256").digest(original))
            assertThat(file.read(buffer, original.size.toLong(), 0, buffer.size)).isEqualTo(-1)
            assertThat(file.read(buffer, original.size + 100L, 0, buffer.size)).isEqualTo(-1)
            for (start in longArrayOf(0, 1, 4096, original.size - 5000L, 777_777, 12, 40_000)) {
                val count = file.read(buffer, start, 3, 2048)
                assertThat(count).isGreaterThan(0)
                assertThat(buffer.copyOfRange(3, 3 + count)).isEqualTo(original.copyOfRange(start.toInt(), start.toInt() + count))
            }
            var resumed = 123_456L
            repeat(6) {
                val count = file.read(buffer, resumed, 0, buffer.size)
                assertThat(buffer.copyOf(count)).isEqualTo(original.copyOfRange(resumed.toInt(), resumed.toInt() + count))
                resumed += count
            }
        }
    }

    @Test fun readingASmallFileAndReopeningWorks() {
        val source = trusted()
        val original = File(dir, "music/root track.opus").readBytes()
        repeat(3) {
            client.open(source, password(), "root track.opus").use { file ->
                val buffer = ByteArray(original.size + 10)
                var total = 0
                while (true) {
                    val count = file.read(buffer, total.toLong(), total, buffer.size - total)
                    if (count == -1) break
                    total += count
                }
                assertThat(buffer.copyOf(total)).isEqualTo(original)
            }
        }
    }

    @Test fun openingAMissingFileFails() {
        val source = trusted()
        assertThrows(IOException::class.java) { client.open(source, password(), "nope.mp3") }
    }

    @Test fun keyAuthenticationWorksForEd25519EcdsaAndPassphraseProtectedKeys() {
        val cases =
            mapOf(
                "id_ed25519" to "",
                "id_ecdsa_new" to "",
                "id_ecdsa_pkcs8" to "",
                "id_rsa_pem" to "",
                "id_rsa_nopass" to "",
                "id_rsa_pass" to params.getProperty("rsaPassphrase"),
                "id_ed25519_pass" to params.getProperty("ed25519Passphrase"),
            )
        for ((name, passphrase) in cases) {
            val secrets = keySecrets(name, passphrase)
            val source = trusted(keyAuth = true, secrets = secrets)
            assertThat(client.list(source, secrets, "").map { it.name }).contains("Big Noise.wav")
        }
    }

    @Test fun aWrongPassphraseOrKeyIsRejected() {
        assertThrows(IOException::class.java) { client.test(folder(keyAuth = true), keySecrets("id_rsa_pass", "wrong")) }
        assertThrows(IOException::class.java) { client.test(folder(keyAuth = true), keySecrets("id_ed25519_pass", "")) }
    }
}
