package io.github.aedev.flow.plugin.pkg

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs [files] as a `.mbplugin` signed by [signer], with ways to tamper with it after signing. */
internal fun signedTestPackage(
    files: Map<String, String>,
    alter: String? = null,
    extra: Pair<String, String>? = null,
    signer: KeyPair = testSigningKey(),
    publicKey: KeyPair = signer,
    signed: Boolean = true,
): ByteArrayInputStream {
    val listing =
        files.entries
            .sortedBy { it.key }
            .joinToString("") { (path, text) -> "${sha256(text.toByteArray())}  $path\n" }
    val signature =
        Signature.getInstance("SHA256withECDSA").run {
            initSign(signer.private)
            update(listing.toByteArray())
            sign()
        }
    val envelope =
        """{"algorithm":"ECDSA_P256_SHA256","publicKey":"${Base64.getEncoder().encodeToString(publicKey.public.encoded)}",""" +
            """"signature":"${Base64.getEncoder().encodeToString(signature)}"}"""
    val out = ByteArrayOutputStream()
    ZipOutputStream(out).use { zip ->
        fun put(
            name: String,
            text: String,
        ) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
        files.forEach { (path, text) -> put(path, if (path == alter) "$text // changed" else text) }
        extra?.let { (path, text) -> put(path, text) }
        if (signed) {
            put("META-INF/CONTENTS", listing)
            put("META-INF/SIGNATURE", envelope)
        }
    }
    return ByteArrayInputStream(out.toByteArray())
}

internal fun testSigningKey(): KeyPair =
    KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
