package io.github.aedev.flow.player.datasource

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.aedev.flow.data.folders.RemoteMusicFile
import java.io.EOFException
import java.io.IOException

@OptIn(UnstableApi::class)
internal class RemoteMusicDataSource(
    private val openFile: (Uri) -> RemoteMusicFile,
) : BaseDataSource(true) {
    private var file: RemoteMusicFile? = null
    private var openedUri: Uri? = null
    private var position = 0L
    private var remaining = 0L
    private var started = false

    override fun open(dataSpec: DataSpec): Long {
        check(file == null)
        transferInitializing(dataSpec)
        try {
            val opened = openFile(dataSpec.uri)
            file = opened
            val size = opened.length
            if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
            position = dataSpec.position
            remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position else minOf(dataSpec.length, size - position)
            openedUri = dataSpec.uri
            started = true
            transferStarted(dataSpec)
            return remaining
        } catch (error: Exception) {
            runCatching { close() }
            throw if (error is IOException) error else IOException("Could not open remote music", error)
        }
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        try {
            val count = checkNotNull(file).read(buffer, position, offset, minOf(length.toLong(), remaining).toInt())
            if (count <= 0) throw EOFException("Remote music ended before its declared length")
            position += count
            remaining -= count
            bytesTransferred(count)
            return count
        } catch (error: Exception) {
            throw if (error is IOException) error else IOException("Could not read remote music", error)
        }
    }

    override fun getUri(): Uri? = openedUri

    override fun close() {
        val opened = file
        file = null
        openedUri = null
        try {
            opened?.close()
        } finally {
            if (started) {
                started = false
                transferEnded()
            }
        }
    }
}
