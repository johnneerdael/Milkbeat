package io.github.aedev.flow.data.folders

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.conscrypt.Conscrypt
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.security.MessageDigest
import java.security.Security
import java.util.Properties

/**
 * Runs against a real SFTP server and is skipped unless the instrumentation argument `sftpConfig` carries a
 * base64 encoded properties file (host, port, user, password, hostKeys, bigSha256 and `key.<name>` entries).
 */
@RunWith(AndroidJUnit4::class)
class SftpMusicClientDeviceTest {
    private val client = SftpMusicClient()
    private lateinit var config: Properties

    @Before fun requireServer() {
        val encoded = InstrumentationRegistry.getArguments().getString("sftpConfig")
        assumeTrue("sftpConfig not set", encoded != null)
        config = Properties().apply { load(Base64.decode(encoded, Base64.DEFAULT).inputStream()) }
        // FlowApplication installs Conscrypt first; the test runner replaces the application, so mirror it.
        if (Security.getProvider("Conscrypt") == null) Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }

    private fun folder(
        hostKey: String = "",
        keyAuth: Boolean = false,
    ) = MusicFolder(
        name = "sftp",
        kind = MusicFolderKind.SFTP,
        host = config.getProperty("host"),
        port = config.getProperty("port").toInt(),
        root = config.getProperty("root"),
        username = config.getProperty("user"),
        hostKey = hostKey,
        keyAuth = keyAuth,
    )

    private fun hostKeys() = config.getProperty("hostKeys").split('|').associateBy { it.substringBefore(' ') }

    private fun password() = MusicFolderSecrets(password = config.getProperty("password"))

    private fun keySecrets(
        name: String,
        passphrase: String = "",
    ) = MusicFolderSecrets(
        password = passphrase,
        privateKey = String(Base64.decode(config.getProperty("key.$name"), Base64.DEFAULT)),
    )

    private fun assertListsAndReads(
        source: MusicFolder,
        secrets: MusicFolderSecrets,
    ) {
        val entries = client.list(source, secrets, "").associateBy { it.name }
        assertTrue(entries.keys.toString(), entries.keys.containsAll(listOf("Big Noise.wav", "Café del Mar", "Spaced Out Album")))
        assertTrue(entries.getValue("Café del Mar").isDirectory)
        assertFalse(entries.containsKey("Track: colon.mp3"))
        val nested = client.list(source, secrets, "Café del Mar/Sébastien – Ünïcode")
        assertEquals(listOf("01 Tröck één.flac"), nested.map { it.name })
        val big = entries.getValue("Big Noise.wav")
        val content = ByteArray(big.size.toInt())
        client.open(source, secrets, "Big Noise.wav").use { file ->
            assertEquals(big.size, file.length)
            var position = 0L
            while (position < content.size) {
                val count = file.read(content, position, position.toInt(), minOf(32 * 1024L, content.size - position).toInt())
                assertTrue(count > 0)
                position += count
            }
            assertEquals(-1, file.read(ByteArray(8), file.length, 0, 8))
            val window = ByteArray(2048)
            for (start in longArrayOf(0, 4096, content.size - 3000L, 777_777)) {
                val count = file.read(window, start, 0, window.size)
                assertTrue(count > 0)
                assertArrayEquals(content.copyOfRange(start.toInt(), start.toInt() + count), window.copyOf(count))
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        assertEquals(config.getProperty("bigSha256"), digest)
    }

    @Test fun everyHostKeyTypeIsNegotiatedPinnedAndVerified() {
        for ((type, fingerprint) in hostKeys()) {
            assertEquals(type, fingerprint, client.test(folder(hostKey = fingerprint), password()).hostKey)
        }
    }

    @Test fun trustOnFirstUseReturnsAServerFingerprint() {
        assertTrue(hostKeys().values.contains(client.test(folder(), password()).hostKey))
    }

    @Test fun aWrongPinnedKeyIsRejected() {
        val forged = hostKeys().getValue("ssh-ed25519").dropLast(2) + "AA"
        assertThrows(IOException::class.java) { client.test(folder(hostKey = forged), password()) }
    }

    @Test fun passwordAuthenticationListsAndReadsWithMatchingChecksum() {
        val source = client.test(folder(), password())
        assertListsAndReads(source, password())
        assertThrows(IOException::class.java) { client.test(folder(), MusicFolderSecrets(password = "nope")) }
    }

    @Test fun ed25519KeyAuthenticationListsAndReadsWithMatchingChecksum() {
        val secrets = keySecrets("id_ed25519")
        val source = client.test(folder(keyAuth = true), secrets)
        assertEquals(hostKeys().values.contains(source.hostKey), true)
        assertListsAndReads(source, secrets)
    }

    @Test fun otherKeyFormatsAndPassphrasesAuthenticate() {
        val cases =
            mapOf(
                "id_ed25519_pass" to config.getProperty("ed25519Passphrase"),
                "id_rsa_pass" to config.getProperty("rsaPassphrase"),
                "id_ecdsa_new" to "",
                "id_ecdsa_pkcs8" to "",
                "id_rsa_pem" to "",
            )
        for ((name, passphrase) in cases) {
            val secrets = keySecrets(name, passphrase)
            val source = client.test(folder(keyAuth = true), secrets)
            assertTrue(name, client.list(source, secrets, "").any { it.name == "Big Noise.wav" })
        }
    }
}
