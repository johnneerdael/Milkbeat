package io.github.aedev.flow.data.library.index

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Release covers as files, so shelves of hundreds of covers load from disk instead of reading each
 * file's tag over the network. A cover's name carries a hash of its bytes: a re-tagged cover gets a
 * new name, which image caches keyed by the path then treat as a new picture.
 */
@Singleton
internal class LibraryArtworkStore
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) {
        private val directory = File(context.filesDir, DIRECTORY)

        fun save(
            releaseKey: String,
            bytes: ByteArray,
            mimeType: String?,
        ): String {
            directory.mkdirs()
            val extension = if (mimeType.orEmpty().endsWith("png", ignoreCase = true)) "png" else "jpg"
            val file = File(directory, "${hash(releaseKey.toByteArray())}-${hash(bytes).take(CONTENT_HASH_LENGTH)}.$extension")
            if (!file.exists()) {
                val partial = File(directory, file.name + ".part")
                partial.writeBytes(bytes)
                if (!partial.renameTo(file)) partial.delete()
            }
            return file.path
        }

        fun delete(path: String) {
            val file = File(path)
            if (file.parentFile == directory) file.delete()
        }

        private fun hash(bytes: ByteArray): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(bytes)
                .take(KEY_HASH_BYTES)
                .joinToString("") { "%02x".format(it) }

        companion object {
            const val DIRECTORY = "library-artwork"
            private const val KEY_HASH_BYTES = 16
            private const val CONTENT_HASH_LENGTH = 12
        }
    }
