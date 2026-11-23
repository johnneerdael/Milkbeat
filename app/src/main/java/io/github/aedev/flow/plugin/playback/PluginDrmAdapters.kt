@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.aedev.flow.plugin.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.drm.ExoMediaDrm
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import nl.neerdael.milkbeat.plugin.AudioDrm
import nl.neerdael.milkbeat.plugin.DrmInitialization
import java.io.IOException
import java.util.Base64

/**
 * The licensed profile's initialization data, validated before any of it reaches Media3: a bounded
 * HTTPS range on a declared CDN, with exactly two distinct 16-byte public track identifiers.
 */
internal fun validatedInitialization(
    drm: AudioDrm,
    allowedHosts: List<String>,
): DrmInitialization? {
    val initialization = drm.initialization ?: return null
    if (initialization.profile != EAC3_WIDEVINE_PROFILE) throw IOException("Unsupported plugin DRM initialization profile")
    if (initialization.lengthBytes !in 8..262_144 || initialization.offsetBytes < 0 ||
        initialization.offsetBytes > Long.MAX_VALUE - initialization.lengthBytes
    ) {
        throw IOException("Invalid plugin DRM initialization range")
    }
    val keyIds = initialization.keyIdsHex.map(::decodeKeyId)
    if (keyIds.size != 2 || keyIds[0].contentEquals(keyIds[1])) throw IOException("Invalid plugin DRM track identifiers")
    checkedPluginMediaUrl(initialization.url, allowedHosts)
    return initialization
}

private const val EAC3_WIDEVINE_PROFILE = "apple-cbcs-eac3-v1"

private fun decodeKeyId(hex: String): ByteArray {
    if (!Regex("^[0-9a-fA-F]{32}$").matches(hex)) throw IOException("Invalid plugin DRM track identifier")
    return ByteArray(16) { index -> hex.substring(index * 2, index * 2 + 2).toInt(16).toByte() }
}

/** The licensed profile's two public track identifiers, decoded for the initialization adapter. */
internal fun initializationKeyIds(initialization: DrmInitialization): List<ByteArray> = initialization.keyIdsHex.map(::decodeKeyId)

/** The provider's service certificate for the Widevine session, bounded as the protocol declares. */
internal fun validatedServiceCertificate(drm: AudioDrm): ByteArray? {
    val certificate = drm.serviceCertificate ?: return null
    val bytes =
        runCatching { Base64.getDecoder().decode(certificate) }.getOrNull()
            ?: throw IOException("Invalid plugin DRM service certificate")
    if (bytes.size !in 128..32_768) throw IOException("Invalid plugin DRM service certificate")
    return bytes
}

/**
 * Applies public track identifiers to the declared initialization bytes, even when Media3 merges
 * the initialization and index ranges. Other bytes stream through without being buffered.
 */
internal fun pluginDrmInitDataSourceFactory(
    upstream: DataSource.Factory,
    initialization: DrmInitialization,
    keyIds: List<ByteArray> = initializationKeyIds(initialization),
): DataSource.Factory =
    DataSource.Factory {
        MappedInitialization(
            upstream.createDataSource(),
            Uri.parse(initialization.url),
            initialization.offsetBytes,
            initialization.lengthBytes,
            keyIds,
        )
    }

private class MappedInitialization(
    private val upstream: DataSource,
    private val uri: Uri,
    private val offset: Long,
    private val length: Int,
    private val keyIds: List<ByteArray>,
) : DataSource by upstream {
    private var data: ByteArray? = null
    private var cursor = 0
    private var bytesBeforeInitialization: Long? = null

    override fun open(dataSpec: DataSpec): Long {
        data = null
        cursor = 0
        bytesBeforeInitialization = null
        val upstreamLength = upstream.open(dataSpec)
        if (dataSpec.uri != uri || dataSpec.position > offset) return upstreamLength
        val prefix = offset - dataSpec.position
        if (!containsInitialization(dataSpec.length, prefix) || !containsInitialization(upstreamLength, prefix)) {
            return upstreamLength
        }
        bytesBeforeInitialization = prefix
        if (prefix == 0L) mapInitialization()
        return upstreamLength
    }

    private fun containsInitialization(
        requestLength: Long,
        prefix: Long,
    ): Boolean = requestLength == C.LENGTH_UNSET.toLong() || (requestLength >= prefix && requestLength - prefix >= length)

    private fun mapInitialization() {
        try {
            val bytes = ByteArray(length)
            var written = 0
            while (written < bytes.size) {
                val count = upstream.read(bytes, written, bytes.size - written)
                if (count <= 0) throw IOException("Incomplete plugin audio initialization")
                written += count
            }
            PluginDrmInitialization.apply(bytes, keyIds)
            data = bytes
            bytesBeforeInitialization = null
        } catch (error: IOException) {
            runCatching { upstream.close() }
            throw error
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        bytesBeforeInitialization?.let { remaining ->
            if (remaining > 0) {
                val count = upstream.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
                if (count == C.RESULT_END_OF_INPUT) throw IOException("Incomplete plugin audio initialization")
                bytesBeforeInitialization = remaining - count
                return count
            }
            mapInitialization()
        }
        val bytes = data ?: return upstream.read(buffer, offset, length)
        val count = minOf(length, bytes.size - cursor)
        bytes.copyInto(buffer, offset, cursor, cursor + count)
        cursor += count
        if (cursor == bytes.size) data = null
        return count
    }

    override fun close() {
        data = null
        bytesBeforeInitialization = null
        upstream.close()
    }
}

/**
 * A Widevine session manager for a provider stream. An optional service certificate is presented
 * to the license server, and the provider's declared session mode applies with or without it. It
 * takes the same permission-checked, refresh-aware license transport as the standard Widevine
 * path, so a certificate session keeps destination checks, grant revocation, license headers and a
 * fresh license URL on every open.
 */
internal fun pluginDrmProvider(
    serviceCertificate: ByteArray?,
    multiSession: Boolean,
    licenseFactory: DataSource.Factory,
): DrmSessionManagerProvider =
    DrmSessionManagerProvider { mediaItem ->
        val provider =
            ExoMediaDrm.Provider { uuid ->
                val drm =
                    runCatching { FrameworkMediaDrm.newInstance(uuid) }.getOrNull()
                        ?: throw IOException("This device cannot open a Widevine session")
                serviceCertificate?.let { drm.setPropertyByteArray("serviceCertificate", it) }
                drm
            }
        DefaultDrmSessionManager
            .Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, provider)
            .setMultiSession(multiSession)
            .build(checkedLicenseCallback(mediaItem, licenseFactory))
    }

/**
 * Key and provisioning requests both go through the bound license transport, never a plain client:
 * the transport re-checks the destination and reads the current accepted license on every open.
 */
internal fun checkedLicenseCallback(
    mediaItem: androidx.media3.common.MediaItem,
    licenseFactory: DataSource.Factory,
): androidx.media3.exoplayer.drm.MediaDrmCallback {
    val license =
        mediaItem.localConfiguration
            ?.drmConfiguration
            ?.licenseUri
            ?.toString()
            ?: throw IOException("The stream declares no license")
    // Provisioning carries no license URL of its own; the transport keeps it on the same source.
    return androidx.media3.exoplayer.drm
        .HttpMediaDrmCallback(license, true, licenseFactory)
}
