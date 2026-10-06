package io.github.aedev.flow.data.folders

import android.content.Context
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.IOException
import java.time.Duration
import javax.inject.Inject

internal class MusicFolderMetadataReader
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: MusicFolderStore,
        private val clients: RemoteMusicClients,
    ) {
        suspend fun read(ref: FolderAudioRef): FolderAudioMetadata {
            val access = store.access(ref.sourceId, ref.revision)
            return runInterruptible(PerformanceDispatcher.diskIO) {
                val retriever = MediaMetadataRetriever()
                var remote: RemoteMusicFile? = null
                try {
                    if (access.source.kind == MusicFolderKind.LOCAL) {
                        val tree = Uri.parse(access.source.treeUri)
                        val uri = Uri.parse(ref.location)
                        if (tree.scheme == "file") {
                            FileMusicFolders.resolve(access.source, ref.location)
                        } else {
                            require(
                                uri.scheme == "content" && uri.authority == tree.authority &&
                                    DocumentsContract.getTreeDocumentId(uri) == DocumentsContract.getTreeDocumentId(tree),
                            )
                        }
                        retriever.setDataSource(context, uri)
                    } else {
                        remote = clients[access.source.kind].open(access.source, access.secrets, ref.location)
                        retriever.setDataSource(RemoteMetadataSource(remote))
                    }
                    readTags(retriever)
                } finally {
                    try {
                        retriever.release()
                    } finally {
                        remote?.close()
                    }
                }
            }
        }

        internal fun readTags(retriever: MediaMetadataRetriever): FolderAudioMetadata {
            fun tag(key: Int): String =
                retriever
                    .extractMetadata(key)
                    ?.trim()
                    ?.takeUnless { it == "<unknown>" }
                    .orEmpty()
            val picture = retriever.embeddedPicture?.takeIf { it.size <= MAX_ARTWORK_BYTES }
            return FolderAudioMetadata(
                title = tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST) },
                album = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                duration =
                    Duration
                        .ofMillis(
                            tag(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0,
                        ).seconds
                        .coerceIn(0, Int.MAX_VALUE.toLong())
                        .toInt(),
                artwork = picture,
            )
        }

        private class RemoteMetadataSource(
            private val file: RemoteMusicFile,
        ) : MediaDataSource() {
            private val length = file.length

            override fun getSize(): Long = length

            override fun readAt(
                position: Long,
                buffer: ByteArray,
                offset: Int,
                size: Int,
            ): Int {
                if (size == 0) return 0
                if (position >= length) return -1
                return try {
                    file.read(buffer, position, offset, minOf(size.toLong(), length - position).toInt())
                } catch (
                    error: Exception,
                ) {
                    throw if (error is IOException) error else IOException("Could not read audio tags", error)
                }
            }

            override fun close() = Unit
        }

        companion object {
            private const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
        }
    }
