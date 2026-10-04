package io.github.aedev.flow.data.library.index

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.aedev.flow.data.folders.MusicFolder
import io.github.aedev.flow.data.folders.MusicFolderKind
import io.github.aedev.flow.data.folders.MusicFolderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Where the library scan is: idle, or reading [read] of the [total] files that changed. */
data class LibraryScanState(
    val running: Boolean = false,
    val read: Int = 0,
    val total: Int = 0,
)

/**
 * Schedules scans of the music folders. A scan starts when the folders change, when asked, and at
 * app start when the index is older than [STALE_AFTER_MS] or was built from other folders.
 */
class LibraryScanJobs
    @Inject
    internal constructor(
        @param:ApplicationContext private val context: Context,
        private val folders: MusicFolderRepository,
        private val dao: LibraryDao,
    ) {
        private val work: WorkManager get() = WorkManager.getInstance(context)

        fun observe(): Flow<LibraryScanState> =
            work.getWorkInfosForUniqueWorkFlow(WORK_NAME).map { infos ->
                val info = infos.lastOrNull()
                if (info == null || info.state.isFinished) {
                    LibraryScanState()
                } else {
                    LibraryScanState(
                        running = info.state == WorkInfo.State.RUNNING,
                        read = info.progress.getInt(SCAN_READ, 0),
                        total = info.progress.getInt(SCAN_TOTAL, 0),
                    )
                }
            }

        /** Scans again now, replacing a scan that is running with the folders as they are. */
        suspend fun rescan() = enqueue(folders.folders.first(), ExistingWorkPolicy.REPLACE)

        suspend fun scanIfStale(now: Long = System.currentTimeMillis()) {
            val current = folders.folders.first()
            val fingerprint = LibraryIndexer.foldersFingerprint(current)
            val scannedFolders = dao.meta(LibraryIndexer.META_SCANNED_FOLDERS)
            if (current.isEmpty() && scannedFolders.isNullOrEmpty()) return
            val scannedAt = dao.meta(LibraryIndexer.META_SCANNED_AT)?.toLongOrNull() ?: 0L
            val pending = work.getWorkInfosForUniqueWorkFlow(WORK_NAME).first().filterNot { it.state.isFinished }
            when {
                // A long first scan records its folders only when it ends; it is already the scan these folders need.
                pending.any { folderTag(fingerprint) in it.tags } -> Unit

                scannedFolders != fingerprint -> enqueue(current, ExistingWorkPolicy.REPLACE)

                now - scannedAt > STALE_AFTER_MS -> enqueue(current, ExistingWorkPolicy.KEEP)
            }
        }

        private fun enqueue(
            current: List<MusicFolder>,
            policy: ExistingWorkPolicy,
        ) {
            val network = if (current.any { it.kind != MusicFolderKind.LOCAL }) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED
            val request =
                OneTimeWorkRequestBuilder<LibraryScanWorker>()
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .addTag(folderTag(LibraryIndexer.foldersFingerprint(current)))
                    .build()
            work.enqueueUniqueWork(WORK_NAME, policy, request)
        }

        private fun folderTag(fingerprint: String) = "library-scan-folders:$fingerprint"

        internal companion object {
            const val WORK_NAME = "library-scan"
            const val STALE_AFTER_MS = 6 * 60 * 60 * 1_000L
            const val BACKOFF_SECONDS = 30L
        }
    }
