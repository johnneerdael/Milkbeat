package io.github.aedev.flow.data.library.index

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.concurrent.futures.await
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceInputStream
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.metadata.flac.PictureFrame
import androidx.media3.extractor.metadata.id3.ApicFrame
import androidx.media3.inspector.MetadataRetriever
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.player.datasource.MusicFolderDataSourceFactory
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.runInterruptible
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject

/** A file's tags, its length, and the cover it carries, if any. */
internal class LibraryFileTags(
    val tags: LibraryTags,
    val durationMs: Long,
    val artwork: ByteArray?,
    val artworkMimeType: String?,
)

/**
 * Reads audio files and playlists in music folders through the player's own data sources, so every
 * kind of folder the player can play is one the index can read. Media3's extractors read only as far
 * as they need to describe the file: its tag block and first frames, not the whole song.
 */
@OptIn(UnstableApi::class)
internal class LibraryFileReader
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        folders: MusicFolderDataSourceFactory,
    ) {
        private val dataSources = folders.wrap(DefaultDataSource.Factory(context))
        private val mediaSources = ProgressiveMediaSource.Factory(dataSources)

        suspend fun tags(uri: Uri): LibraryFileTags =
            MetadataRetriever.Builder(context, MediaItem.fromUri(uri)).setMediaSourceFactory(mediaSources).build().use { retriever ->
                val groups = retriever.retrieveTrackGroups().await()
                val durationUs = retriever.retrieveDurationUs().await()
                val entries =
                    buildList {
                        for (group in 0 until groups.length) {
                            val trackGroup = groups[group]
                            for (track in 0 until trackGroup.length) trackGroup.getFormat(track).metadata?.let { addAll(it.entries()) }
                        }
                    }
                val cover = entries.cover()
                val tags = libraryTags(entries)
                LibraryFileTags(
                    tags = tags,
                    durationMs = if (durationUs != C.TIME_UNSET && durationUs > 0) durationUs / MICROS_PER_MILLI else tags.lengthMs ?: 0L,
                    artwork = cover?.first,
                    artworkMimeType = cover?.second,
                )
            }

        /** A playlist's text: UTF-8 when it decodes as such, as M3U8 and most tools write it, else Windows-1252. */
        suspend fun text(uri: Uri): String =
            runInterruptible(PerformanceDispatcher.networkIO) {
                val source = dataSources.createDataSource()
                val bytes =
                    DataSourceInputStream(source, DataSpec(uri)).use { stream ->
                        val out = ByteArrayOutputStream()
                        val chunk = ByteArray(READ_CHUNK_BYTES)
                        while (true) {
                            val read = stream.read(chunk)
                            if (read < 0) break
                            out.write(chunk, 0, read)
                            require(out.size() <= MAX_PLAYLIST_BYTES) { "Playlist too large" }
                        }
                        out.toByteArray()
                    }
                try {
                    Charsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes))
                        .toString()
                } catch (_: CharacterCodingException) {
                    String(bytes, Charset.forName("windows-1252"))
                }
            }

        private fun Metadata.entries(): List<Metadata.Entry> = (0 until length()).map(::get)

        /** The front cover when the file names one, else its first picture. */
        private fun List<Metadata.Entry>.cover(): Pair<ByteArray, String?>? {
            val pictures =
                mapNotNull { entry ->
                    when (entry) {
                        is ApicFrame -> Triple(entry.pictureType, entry.pictureData, entry.mimeType)
                        is PictureFrame -> Triple(entry.pictureType, entry.pictureData, entry.mimeType)
                        else -> null
                    }
                }.filter { it.second.isNotEmpty() && it.second.size <= MAX_ARTWORK_BYTES }
            val picture = pictures.firstOrNull { it.first == FRONT_COVER } ?: pictures.firstOrNull() ?: return null
            return picture.second to picture.third
        }

        private companion object {
            const val MICROS_PER_MILLI = 1_000L
            const val FRONT_COVER = 3
            const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
            const val MAX_PLAYLIST_BYTES = 2 * 1024 * 1024
            const val READ_CHUNK_BYTES = 16 * 1024
        }
    }
