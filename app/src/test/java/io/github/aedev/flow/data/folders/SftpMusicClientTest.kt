package io.github.aedev.flow.data.folders

import com.google.common.truth.Truth.assertThat
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.userauth.method.AuthKeyboardInteractive
import net.schmizz.sshj.userauth.method.AuthPassword
import net.schmizz.sshj.userauth.method.AuthPublickey
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PublicKey
import java.util.Base64

class SftpMusicClientTest {
    private val ed25519 = "AAAAC3NzaC1lZDI1NTE5AAAAICYz7Ykq9r0ROA5WAx6uEx/G1jEnlnmMtk2jQ88/m3sT"
    private val ecdsa =
        "AAAAE2VjZHNhLXNoYTItbmlzdHAyNTYAAAAIbmlzdHAyNTYAAABBBKiE3hROl+lkCjuEFcCZjEl8oFI4BhkqnBnQ+KaRlqxesRlrCshhiXG6G2mjHVTemt4uhhYU6us7QNj7ZKqWhKk="

    private fun key(blob: String): PublicKey = Buffer.PlainBuffer(Base64.getDecoder().decode(blob)).readPublicKey()

    private fun sftpFolder(
        root: String = "",
        keyAuth: Boolean = false,
    ) = MusicFolder(name = "sftp", kind = MusicFolderKind.SFTP, host = "host", root = root, username = "user", keyAuth = keyAuth)

    private fun attributes(
        type: Int,
        size: Long = 1234,
        mtime: Long = 1_700_000_000,
    ) = FileAttributes(0, size, 0, 0, FileMode(type or 0b110_100_100), 0, mtime, emptyMap())

    @Test fun fingerprintsMatchSshKeygen() {
        assertThat(sftpFingerprint(key(ed25519))).isEqualTo("ssh-ed25519 SHA256:+xlW/jHcqgEU8Hdvu8D2i606A5mrZ3DdM3WzDqzXcg0")
        assertThat(sftpFingerprint(key(ecdsa))).isEqualTo("ecdsa-sha2-nistp256 SHA256:bw+zPHLMW6cNYUp4PKuFJ1IzkZbt1K79RHKZHAzrPzg")
    }

    @Test fun trustOnFirstUseAcceptsAndRecordsTheKey() {
        val verifier = SftpHostKeyVerifier(null)
        assertThat(verifier.presented).isNull()
        assertThat(verifier.verify("host", 22, key(ed25519))).isTrue()
        assertThat(verifier.presented).isEqualTo(sftpFingerprint(key(ed25519)))
        assertThat(verifier.findExistingAlgorithms("host", 22)).isEmpty()
    }

    @Test fun aPinnedKeyIsVerifiedStrictly() {
        val verifier = SftpHostKeyVerifier(sftpFingerprint(key(ed25519)))
        assertThat(verifier.verify("host", 22, key(ed25519))).isTrue()
        assertThat(verifier.verify("host", 22, key(ecdsa))).isFalse()
        assertThat(verifier.findExistingAlgorithms("host", 22)).containsExactly("ssh-ed25519")
    }

    @Test fun aPinnedKeyOnlyMatchesItsExactFingerprint() {
        val pinned = sftpFingerprint(key(ed25519))
        assertThat(SftpHostKeyVerifier("ssh-rsa " + pinned.substringAfter(' ')).verify("host", 22, key(ed25519))).isFalse()
        assertThat(SftpHostKeyVerifier(pinned.dropLast(1)).verify("host", 22, key(ed25519))).isFalse()
        assertThat(SftpHostKeyVerifier("").verify("host", 22, key(ed25519))).isFalse()
    }

    @Test fun rsaHostKeysAllowEverySignatureAlgorithm() {
        assertThat(sftpHostKeyAlgorithms("ssh-rsa SHA256:x")).containsExactly("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa").inOrder()
        assertThat(sftpHostKeyAlgorithms("ecdsa-sha2-nistp384 SHA256:x")).containsExactly("ecdsa-sha2-nistp384")
    }

    @Test fun entriesKeepFoldersAndMusicFilesOnly() {
        val folder = sftpEntry("Albums", "Albums", attributes(0x4000))!!
        assertThat(folder.isDirectory).isTrue()
        assertThat(folder.size).isEqualTo(0)
        val track = sftpEntry("a.flac", "Albums/a.flac", attributes(0x8000))!!
        assertThat(track).isEqualTo(MusicFolderEntry("a.flac", "Albums/a.flac", false, 1234, 1_700_000_000_000))
        assertThat(sftpEntry("notes.txt", "notes.txt", attributes(0x8000))).isNull()
        assertThat(sftpEntry("Mix.m3u", "Mix.m3u", attributes(0x8000))).isNull()
        assertThat(sftpEntry("Mix.m3u", "Mix.m3u", attributes(0x8000), includePlaylists = true)?.location).isEqualTo("Mix.m3u")
        assertThat(sftpEntry("a.mp3", "a.mp3", attributes(0x1000))).isNull()
        assertThat(sftpEntry("a.mp3", "a.mp3", attributes(0xA000))).isNull()
    }

    @Test fun serverPathsFollowTheRootRules() {
        assertThat(serverPath(sftpFolder(), "")).isEqualTo(".")
        assertThat(serverPath(sftpFolder(), "Albums/A")).isEqualTo("Albums/A")
        assertThat(serverPath(sftpFolder("music"), "")).isEqualTo("music")
        assertThat(serverPath(sftpFolder("/srv/music/"), "Albums")).isEqualTo("/srv/music/Albums")
        assertThat(serverPath(sftpFolder("/"), "")).isEqualTo("/")
        assertThrows(IllegalArgumentException::class.java) { serverPath(sftpFolder("music"), "../etc") }
    }

    @Test fun passwordFoldersTryPasswordThenKeyboardInteractive() {
        val methods = authMethods(SSHClient(), sftpFolder(), MusicFolderSecrets(password = "pw"))
        assertThat(methods.map { it::class }).containsExactly(AuthPassword::class, AuthKeyboardInteractive::class).inOrder()
    }

    @Test fun keyFoldersUsePublicKeyAuthAndRequireAKey() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val pem =
            "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(pair.private.encoded) +
                "\n-----END PRIVATE KEY-----\n"
        val methods = authMethods(SSHClient(), sftpFolder(keyAuth = true), MusicFolderSecrets(privateKey = pem))
        assertThat(methods.map { it::class }).containsExactly(AuthPublickey::class)
        assertThrows(IllegalArgumentException::class.java) {
            authMethods(SSHClient(), sftpFolder(keyAuth = true), MusicFolderSecrets(password = "pw"))
        }
    }
}
