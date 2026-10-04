package io.github.aedev.flow.data.folders

import android.net.Uri
import io.github.aedev.flow.utils.PerformanceDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicFolderRepository
    @Inject
    constructor(
        private val store: MusicFolderStore,
        private val documents: DocumentMusicFolders,
        private val smb: SmbMusicClient,
    ) {
        val folders = store.folders

        suspend fun addLocal(uri: Uri) {
            val existingGrant = runInterruptible(PerformanceDispatcher.diskIO) { documents.hasAccess(uri) }
            try {
                val source = runInterruptible(PerformanceDispatcher.diskIO) { documents.add(uri) }
                if (folders.first().none { it.kind == MusicFolderKind.LOCAL && it.treeUri == source.treeUri }) store.save(source)
            } catch (error: Exception) {
                withContext(NonCancellable + PerformanceDispatcher.diskIO) {
                    if (!existingGrant && folders.first().none { it.treeUri == uri.toString() }) runCatching { documents.release(uri) }
                }
                throw error
            }
        }

        suspend fun save(
            source: MusicFolder,
            password: String?,
        ) = store.save(source, password)

        suspend fun remove(source: MusicFolder) {
            store.remove(source.id)
            if (source.kind == MusicFolderKind.LOCAL && folders.first().none { it.treeUri == source.treeUri }) {
                withContext(NonCancellable + PerformanceDispatcher.diskIO) { runCatching { documents.release(source) } }
            }
        }

        suspend fun test(
            source: MusicFolder,
            password: String?,
        ) {
            val secret = password ?: store.access(source.id, source.revision).password
            runInterruptible(PerformanceDispatcher.networkIO) { smb.test(source, secret) }
        }

        suspend fun list(
            source: MusicFolder,
            location: String,
            includePlaylists: Boolean = false,
        ): List<MusicFolderEntry> {
            val entries =
                if (source.kind == MusicFolderKind.LOCAL) {
                    runInterruptible(PerformanceDispatcher.diskIO) { documents.list(source, location, includePlaylists) }
                } else {
                    val access = store.access(source.id, source.revision)
                    runInterruptible(
                        PerformanceDispatcher.networkIO,
                    ) { smb.list(access.source, access.password, location, includePlaylists) }
                }
            return withContext(PerformanceDispatcher.diskIO) {
                entries.sortedWith(
                    compareBy<MusicFolderEntry> { !it.isDirectory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        .thenBy { it.location },
                )
            }
        }
    }
