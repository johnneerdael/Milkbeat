package io.github.aedev.flow.data.update

import android.os.SystemClock
import io.github.aedev.flow.BuildConfig
import io.github.aedev.flow.data.local.LocalDataManager
import io.github.aedev.flow.updater.AppUpdateInstaller
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val RELEASE_BUILD_TYPE = "release"

/** When the foreground checks run: shortly after launch, then six hours after the previous one. */
internal object AutoUpdateSchedule {
    val FIRST_CHECK_DELAY_MS: Long = TimeUnit.SECONDS.toMillis(10)
    val INTERVAL_MS: Long = TimeUnit.HOURS.toMillis(6)

    fun delayUntilNextCheck(
        lastCheckMs: Long?,
        nowMs: Long,
    ): Long =
        if (lastCheckMs == null) {
            FIRST_CHECK_DELAY_MS
        } else {
            (lastCheckMs + INTERVAL_MS - nowMs).coerceIn(FIRST_CHECK_DELAY_MS, INTERVAL_MS)
        }
}

/**
 * Keeps a release build current while it is open: looks for a newer release at launch and every six
 * hours, downloads it in the background, and says when it is ready. Installing waits until the user
 * chooses Install.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class AutoUpdater
    @Inject
    constructor(
        private val updates: UpdateRepository,
        private val installer: AppUpdateInstaller,
        private val dataManager: LocalDataManager,
    ) {
        /** Debug and nightly builds carry another package name, so a release could never install over them. */
        val isAvailable: Boolean = installer.isAvailable && BuildConfig.BUILD_TYPE == RELEASE_BUILD_TYPE

        val enabled: Flow<Boolean> = dataManager.automaticUpdates

        /** Elapsed-realtime of this process's last check; null until the launch check has run. */
        private var lastCheckMs: Long? = null

        /** The newer release whose download is ready to install, or null while there is none or it was skipped. */
        val ready: Flow<AppRelease?> =
            updates.latest
                .flatMapLatest { release ->
                    if (release == null || !isAvailable) {
                        flowOf(null)
                    } else {
                        combine(installer.state(release.version), dataManager.skippedUpdateVersion) { download, skipped ->
                            release.takeIf { download == UpdateDownload.Ready && it.version != skipped }
                        }
                    }
                }.distinctUntilChanged()

        suspend fun setEnabled(enabled: Boolean) {
            dataManager.setAutomaticUpdates(enabled)
            if (enabled) {
                if (isAvailable) downloadNewRelease()
            } else {
                installer.cancel()
                installer.clean(keepVersion = null)
            }
        }

        /** Checks for as long as the caller keeps it running, i.e. while the app is in the foreground. */
        suspend fun checkWhileForeground() {
            if (!isAvailable) return
            while (true) {
                delay(AutoUpdateSchedule.delayUntilNextCheck(lastCheckMs, SystemClock.elapsedRealtime()))
                lastCheckMs = SystemClock.elapsedRealtime()
                if (enabled.first()) downloadNewRelease()
            }
        }

        private suspend fun downloadNewRelease() {
            val release =
                try {
                    updates.fetch()
                } catch (_: IOException) {
                    null
                } ?: return
            if (release.apk == null || release.version == dataManager.skippedUpdateVersion.first()) return
            // A bad checksum or a foreign package would fail the same way again; only a lost connection or full storage is retried.
            val retry =
                when (val download = installer.state(release.version).first()) {
                    UpdateDownload.Idle -> true
                    is UpdateDownload.Failed -> download.reason == UpdateFailure.NETWORK || download.reason == UpdateFailure.STORAGE
                    else -> false
                }
            if (retry) installer.download(release)
        }
    }
