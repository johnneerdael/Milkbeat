package io.github.aedev.flow.data.folders

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.common.SecurityUtils
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import java.io.IOException
import java.security.PublicKey
import java.util.Base64

/** OpenSSH style SHA-256 fingerprint prefixed by the key type, as printed by `ssh-keygen -lf`. */
internal fun sftpFingerprint(key: PublicKey): String {
    SftpSecurity.install()
    val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
    val digest = SecurityUtils.getMessageDigest("SHA-256").digest(blob)
    return "${KeyType.fromKey(key)} SHA256:${Base64.getEncoder().withoutPadding().encodeToString(digest)}"
}

/** Signature algorithms a server may use to prove the key behind a pinned [fingerprint]. */
internal fun sftpHostKeyAlgorithms(fingerprint: String): List<String> =
    when (val type = fingerprint.substringBefore(' ')) {
        "ssh-rsa" -> listOf("rsa-sha2-512", "rsa-sha2-256", "ssh-rsa")
        else -> listOf(type)
    }

class SftpHostKeyMismatchException(
    cause: Throwable,
) : IOException("The server key does not match the pinned key", cause)

/** Verifies against [pinned] strictly, or accepts and records the presented key when [pinned] is null. */
internal class SftpHostKeyVerifier(
    val pinned: String?,
) : HostKeyVerifier {
    @Volatile
    var presented: String? = null
        private set

    override fun verify(
        hostname: String,
        port: Int,
        key: PublicKey,
    ): Boolean {
        val fingerprint = sftpFingerprint(key)
        presented = fingerprint
        return pinned == null || pinned == fingerprint
    }

    override fun findExistingAlgorithms(
        hostname: String,
        port: Int,
    ): List<String> = pinned?.let(::sftpHostKeyAlgorithms).orEmpty()
}
