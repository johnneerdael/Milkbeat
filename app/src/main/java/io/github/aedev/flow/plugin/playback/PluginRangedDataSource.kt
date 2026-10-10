@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package io.github.aedev.flow.plugin.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.HttpUtil
import androidx.media3.datasource.TransferListener

/** Network transport that keeps every request of a stream tagged with a declared range size within it. */
internal fun pluginRangedDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory =
    DataSource.Factory { PluginRangedDataSource(upstream.createDataSource()) }

/** Tags [this] request so the network transport cuts it into the plugin's declared ranges. */
internal fun DataSpec.withRangePolicy(policy: PluginRangePolicy?): DataSpec =
    if (policy is PluginRangePolicy.Declared) buildUpon().setCustomData(policy).build() else this

/**
 * Serves one open as consecutive requests of at most the declared size, reopening upstream as each
 * range ends. Callers above it (the player's loader, the cache and the downloader) see one transfer
 * with its true length: Media3 takes a bounded open as the end of the file, so the ranges must stay
 * below them. Requests without a declared policy pass straight through.
 */
internal class PluginRangedDataSource(
    private val upstream: DataSource,
) : DataSource {
    private var spec: DataSpec? = null
    private var maxBytes = 0L
    private var position = 0L
    private var end = C.LENGTH_UNSET.toLong()
    private var rangeRemaining = 0L

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val policy = dataSpec.customData as? PluginRangePolicy.Declared
        if (policy == null) {
            spec = null
            return upstream.open(dataSpec)
        }
        spec = dataSpec
        maxBytes = policy.maxBytes
        position = dataSpec.position
        end = if (dataSpec.length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else dataSpec.position + dataSpec.length
        openRange()
        if (end == C.LENGTH_UNSET.toLong()) {
            val size =
                HttpUtil.getDocumentSize(
                    upstream.responseHeaders.entries
                        .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }
                        ?.value
                        ?.firstOrNull(),
                )
            if (size != C.LENGTH_UNSET.toLong()) end = size
        }
        return if (end == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else end - dataSpec.position
    }

    /** Opens the next range; false once the server has nothing past [position]. */
    private fun openRange(): Boolean {
        val request = checkNotNull(spec)
        val length = if (end == C.LENGTH_UNSET.toLong()) maxBytes else minOf(maxBytes, end - position)
        val range =
            request
                .buildUpon()
                .setPosition(position)
                .setLength(length)
                .build()
        val served =
            try {
                upstream.open(range)
            } catch (e: HttpDataSource.InvalidResponseCodeException) {
                if (e.responseCode == 416 && position > request.position && end == C.LENGTH_UNSET.toLong()) return false
                throw e
            }
        rangeRemaining = if (served == C.LENGTH_UNSET.toLong()) length else minOf(served, length)
        return true
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (spec == null) return upstream.read(buffer, offset, length)
        while (true) {
            if (end != C.LENGTH_UNSET.toLong() && position >= end) return C.RESULT_END_OF_INPUT
            val read = upstream.read(buffer, offset, length)
            if (read != C.RESULT_END_OF_INPUT) {
                position += read
                rangeRemaining -= read
                return read
            }
            // A short range from a server of unknown size is its end; a full one may have more.
            if (end == C.LENGTH_UNSET.toLong() && rangeRemaining > 0) return C.RESULT_END_OF_INPUT
            upstream.close()
            if (!openRange()) return C.RESULT_END_OF_INPUT
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        spec = null
        upstream.close()
    }
}
