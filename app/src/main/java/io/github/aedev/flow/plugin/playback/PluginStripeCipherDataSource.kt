@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.aedev.flow.plugin.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import nl.neerdael.milkbeat.plugin.AudioCipher
import nl.neerdael.milkbeat.plugin.AudioCipherScheme
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val STRIPE_BLOCK = 2048
private const val STRIPE_INTERVAL = 3L
private const val STRIPE_KEY_BYTES = 16
private val StripeIv = ByteArray(8) { it.toByte() }

/** The 16-byte key of a valid [AudioCipher]; null for a malformed one. */
internal fun AudioCipher.keyBytes(): ByteArray? {
    if (keyHex.length != STRIPE_KEY_BYTES * 2 || keyHex.any { Character.digit(it, 16) < 0 }) return null
    return when (scheme) {
        AudioCipherScheme.BF_CBC_STRIPE -> ByteArray(STRIPE_KEY_BYTES) { keyHex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}

/**
 * Cached bytes sit beneath the decryptor, so their identity names the key that reads them. Clear
 * streams keep an empty suffix, leaving their existing cache identity unchanged.
 */
internal fun AudioCipher?.cacheIdentity(): String =
    this?.let { cipher ->
        val digest = MessageDigest.getInstance("SHA-256").digest(cipher.keyHex.lowercase().toByteArray())
        ":${cipher.scheme}:" + digest.take(8).joinToString("") { "%02x".format(it) }
    } ?: ""

internal fun pluginStripeCipherDataSourceFactory(
    upstream: DataSource.Factory,
    cipher: AudioCipher,
): DataSource.Factory {
    val key = cipher.keyBytes() ?: throw IOException("The provider's stream key is malformed")
    return DataSource.Factory { PluginStripeCipherDataSource(upstream.createDataSource(), key) }
}

/**
 * Removes BF_CBC_STRIPE encryption while reading: only whole blocks whose index is a multiple of
 * three are encrypted, each from the fixed IV. A block's index is its absolute offset, so every
 * open starts upstream at a block boundary and extends a bounded range to one, then drops the
 * bytes outside the requested range. Upstream keeps the encrypted bytes, cache included.
 */
internal class PluginStripeCipherDataSource(
    private val upstream: DataSource,
    key: ByteArray,
) : DataSource {
    private val key = SecretKeySpec(key, "Blowfish")
    private val cipher = Cipher.getInstance("Blowfish/CBC/NoPadding")
    private val block = ByteArray(STRIPE_BLOCK)
    private var blockIndex = 0L
    private var blockFill = 0
    private var blockRead = 0
    private var skip = 0
    private var remaining = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val aligned = dataSpec.position - dataSpec.position % STRIPE_BLOCK
        skip = (dataSpec.position - aligned).toInt()
        val upstreamLength =
            if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
                C.LENGTH_UNSET.toLong()
            } else {
                (skip + dataSpec.length + STRIPE_BLOCK - 1) / STRIPE_BLOCK * STRIPE_BLOCK
            }
        val opened =
            upstream.open(
                dataSpec
                    .buildUpon()
                    .setPosition(aligned)
                    .setLength(upstreamLength)
                    .build(),
            )
        blockIndex = aligned / STRIPE_BLOCK
        blockFill = 0
        blockRead = 0
        val available = if (opened == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else maxOf(0L, opened - skip)
        remaining =
            when {
                dataSpec.length == C.LENGTH_UNSET.toLong() -> available
                available == C.LENGTH_UNSET.toLong() -> dataSpec.length
                else -> minOf(dataSpec.length, available)
            }
        return remaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        if (blockRead == blockFill && !nextBlock()) return C.RESULT_END_OF_INPUT
        var count = minOf(length, blockFill - blockRead)
        if (remaining != C.LENGTH_UNSET.toLong()) count = minOf(count.toLong(), remaining).toInt()
        System.arraycopy(block, blockRead, buffer, offset, count)
        blockRead += count
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
        return count
    }

    /** Reads and clears the next block; false once upstream holds nothing past the skipped prefix. */
    private fun nextBlock(): Boolean {
        blockFill = 0
        while (blockFill < STRIPE_BLOCK) {
            val read = upstream.read(block, blockFill, STRIPE_BLOCK - blockFill)
            if (read == C.RESULT_END_OF_INPUT) break
            blockFill += read
        }
        if (blockFill == STRIPE_BLOCK && blockIndex % STRIPE_INTERVAL == 0L) {
            cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(StripeIv))
            cipher.doFinal(block, 0, STRIPE_BLOCK, block, 0)
        }
        blockIndex++
        blockRead = minOf(skip, blockFill)
        skip = 0
        return blockRead < blockFill
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        blockFill = 0
        blockRead = 0
        upstream.close()
    }
}
