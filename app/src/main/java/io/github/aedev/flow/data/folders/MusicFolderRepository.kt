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
        private val clients: RemoteMusicClients,
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
            privateKey: String? = null,
        ) {
            val trusted =
                if (source.kind == MusicFolderKind.SFTP &&
                    source.hostKey.isBlank()
                ) {
                    test(source, password, privateKey)
                } else {
                    source
                }
            store.save(trusted, password, privateKey)
        }

        suspend fun remove(source: MusicFolder) {
            store.remove(source.id)
            if (source.kind == MusicFolderKind.LOCAL && folders.first().none { it.treeUri == source.treeUri }) {
                withContext(NonCancellable + PerformanceDispatcher.diskIO) { runCatching { documents.release(source) } }
            }
        }

        suspend fun test(
            source: MusicFolder,
            password: String?,
            privateKey: String? = null,
        ): MusicFolder {
            val stored =
                if (password == null ||
                    privateKey == null
                ) {
                    store.access(source.id, source.revision).secrets
                } else {
                    MusicFolderSecrets()
                }
            val secrets = MusicFolderSecrets(password ?: stored.password, privateKey ?: stored.privateKey)
            return runInterruptible(PerformanceDispatcher.networkIO) { clients[source.kind].test(source, secrets) }
        }

        suspend fun list(
            source: MusicFolder,
            location: String,
        ): List<MusicFolderEntry> {
            val entries =
                if (source.kind == MusicFolderKind.LOCAL) {
                    runInterruptible(PerformanceDispatcher.diskIO) { documents.list(source, location) }
                } else {
                    val access = store.access(source.id, source.revision)
                    runInterruptible(
                        PerformanceDispatcher.networkIO,
                    ) { clients[access.source.kind].list(access.source, access.secrets, location) }
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
