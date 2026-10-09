package io.github.aedev.flow.plugin.pkg

import nl.neerdael.milkbeat.plugin.PLUGIN_API_VERSION
import nl.neerdael.milkbeat.plugin.PLUGIN_FORMAT_VERSION
import nl.neerdael.milkbeat.plugin.PluginJson
import nl.neerdael.milkbeat.plugin.PluginManifest
import java.io.InputStream
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.zip.ZipInputStream

private const val MANIFEST = "manifest.json"
private const val CONTENTS = "META-INF/CONTENTS"
private const val SIGNATURE = "META-INF/SIGNATURE"
private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
private const val SIGNATURE_SCHEME = "ECDSA_P256_SHA256"
private const val MAX_ENTRIES = 512
private const val MAX_ENTRY_BYTES = 24L * 1024 * 1024
private const val MAX_TOTAL_BYTES = 64L * 1024 * 1024
private const val BUFFER_BYTES = 64 * 1024
private val PLUGIN_ID = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_-]+)+$")

/** A `.mbplugin` whose contents, signature and manifest all checked out. */
class PluginPackage(
    val manifest: PluginManifest,
    val files: Map<String, ByteArray>,
    /** SHA-256 of the author's public key: the identity every update must share. */
    val signerFingerprint: String,
)

class PluginPackageException(
    val reason: Reason,
    message: String,
    /** For [Reason.INCOMPATIBLE]: the manifest whose container format or minimum API this Milkbeat is too old for. */
    val incompatible: PluginManifest? = null,
) : Exception(message) {
    enum class Reason { MALFORMED, TOO_LARGE, TAMPERED, UNSIGNED, INCOMPATIBLE }
}

/**
 * Reads a `.mbplugin`: a ZIP whose `META-INF/CONTENTS` lists the SHA-256 of every other entry and
 * whose `META-INF/SIGNATURE` signs that listing with the author's ECDSA P-256 key. Anything unlisted,
 * altered, oversized or signed wrongly is refused before a byte of it is used.
 */
object PluginPackageReader {
    fun read(input: InputStream): PluginPackage {
        val entries = unzip(input)
        val contents = entries[CONTENTS] ?: throw PluginPackageException(PluginPackageException.Reason.UNSIGNED, "No $CONTENTS")
        val signature = entries[SIGNATURE] ?: throw PluginPackageException(PluginPackageException.Reason.UNSIGNED, "No $SIGNATURE")
        val publicKey = verifySignature(contents, signature)
        val files = entries - CONTENTS - SIGNATURE
        verifyContents(contents.decodeToString(), files)
        val manifest = parseManifest(files[MANIFEST])
        if (manifest.entry !in
            files
        ) {
            throw PluginPackageException(PluginPackageException.Reason.MALFORMED, "Entry ${manifest.entry} is missing")
        }
        return PluginPackage(manifest, files, fingerprint(publicKey))
    }

    fun fingerprint(key: PublicKey): String = MessageDigest.getInstance("SHA-256").digest(key.encoded).toHex()

    private fun unzip(input: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name
                if (name.startsWith("/") || name.split('/').any { it == ".." || it.isEmpty() }) {
                    throw PluginPackageException(PluginPackageException.Reason.MALFORMED, "Unsafe entry name $name")
                }
                if (name in entries) throw PluginPackageException(PluginPackageException.Reason.MALFORMED, "Duplicate entry $name")
                if (entries.size >= MAX_ENTRIES) throw PluginPackageException(PluginPackageException.Reason.TOO_LARGE, "Too many entries")
                val bytes = readCapped(zip, name, MAX_TOTAL_BYTES - total)
                total += bytes.size
                entries[name] = bytes
            }
        }
        if (entries.isEmpty()) throw PluginPackageException(PluginPackageException.Reason.MALFORMED, "Not a plugin package")
        return entries
    }

    // Sizes in a ZIP header can lie, so the limit is enforced on the bytes actually inflated.
    private fun readCapped(
        input: InputStream,
        name: String,
        remaining: Long,
    ): ByteArray {
        val limit = minOf(MAX_ENTRY_BYTES, remaining)
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > limit) throw PluginPackageException(PluginPackageException.Reason.TOO_LARGE, "$name is too large")
        }
        return out.toByteArray()
    }

    private fun verifySignature(
        contents: ByteArray,
        signatureFile: ByteArray,
    ): PublicKey {
        val envelope =
            runCatching { PluginJson.decodeFromString(SignatureEnvelope.serializer(), signatureFile.decodeToString()) }
                .getOrElse { throw PluginPackageException(PluginPackageException.Reason.UNSIGNED, "Unreadable $SIGNATURE") }
        if (envelope.algorithm != SIGNATURE_SCHEME) {
            throw PluginPackageException(PluginPackageException.Reason.UNSIGNED, "Unsupported signature ${envelope.algorithm}")
        }
        val key =
            runCatching {
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(envelope.publicKey)))
            }.getOrElse { throw PluginPackageException(PluginPackageException.Reason.UNSIGNED, "Unreadable public key") }
        val valid =
            runCatching {
                Signature.getInstance(SIGNATURE_ALGORITHM).run {
                    initVerify(key)
                    update(contents)
                    verify(Base64.getDecoder().decode(envelope.signature))
                }
            }.getOrDefault(false)
        if (!valid) throw PluginPackageException(PluginPackageException.Reason.TAMPERED, "The signature does not match")
        return key
    }

    private fun verifyContents(
        listing: String,
        files: Map<String, ByteArray>,
    ) {
        val listed =
            listing
                .lineSequence()
                .filter { it.isNotBlank() }
                .associate { line ->
                    val hash = line.substringBefore("  ")
                    val path = line.substringAfter("  ", missingDelimiterValue = "")
                    if (hash.length != 64 || path.isEmpty()) {
                        throw PluginPackageException(PluginPackageException.Reason.TAMPERED, "Malformed $CONTENTS line")
                    }
                    path to hash.lowercase()
                }
        if (listed.keys !=
            files.keys
        ) {
            throw PluginPackageException(PluginPackageException.Reason.TAMPERED, "$CONTENTS does not list every file")
        }
        files.forEach { (path, bytes) ->
            if (MessageDigest.getInstance("SHA-256").digest(bytes).toHex() != listed[path]) {
                throw PluginPackageException(PluginPackageException.Reason.TAMPERED, "$path was altered")
            }
        }
    }

    private fun parseManifest(bytes: ByteArray?): PluginManifest {
        bytes ?: throw PluginPackageException(PluginPackageException.Reason.MALFORMED, "No $MANIFEST")
        val manifest =
            runCatching { PluginJson.decodeFromString(PluginManifest.serializer(), bytes.decodeToString()) }
                .getOrElse { throw PluginPackageException(PluginPackageException.Reason.MALFORMED, "Unreadable $MANIFEST: ${it.message}") }
        if (!PLUGIN_ID.matches(
                manifest.id,
            )
        ) {
            throw PluginPackageException(PluginPackageException.Reason.MALFORMED, "Invalid id ${manifest.id}")
        }
        if (manifest.format > PLUGIN_FORMAT_VERSION || manifest.api.min > PLUGIN_API_VERSION) {
            throw PluginPackageException(
                PluginPackageException.Reason.INCOMPATIBLE,
                "${manifest.name} needs a newer Milkbeat (format ${manifest.format}, API ${manifest.api.min})",
                incompatible = manifest,
            )
        }
        return manifest
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}

@kotlinx.serialization.Serializable
private data class SignatureEnvelope(
    val algorithm: String,
    val publicKey: String,
    val signature: String,
)
