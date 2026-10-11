package io.github.aedev.flow.updater

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Receives the MicroG-RE-TV install session's result: shows the system confirmation, or records a failure. */
@AndroidEntryPoint
class MicroGInstallReceiver : BroadcastReceiver() {
    @Inject
    lateinit var installer: MicroGInstaller

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (!installer.awaits(sessionId)) {
            // The sign-in that committed it has moved on: its prompt, success or failure concerns no attempt now.
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            return
        }
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                // An installer whose confirmation cannot be started fails the install instead of the app.
                try {
                    confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } ?: installer.reportFailure()
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "MicroG-RE-TV install confirmation could not open", e)
                    installer.reportFailure()
                } catch (e: SecurityException) {
                    Log.w(TAG, "MicroG-RE-TV install confirmation could not open", e)
                    installer.reportFailure()
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                installer.reportInstalled()
            }

            else -> {
                Log.w(TAG, "MicroG-RE-TV install failed ($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                installer.reportFailure()
            }
        }
    }

    private companion object {
        const val TAG = "MicroGInstallReceiver"
    }
}
