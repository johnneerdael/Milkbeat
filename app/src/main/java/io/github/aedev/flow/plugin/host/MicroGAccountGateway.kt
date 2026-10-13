package io.github.aedev.flow.plugin.host

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.os.BundleCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import nl.neerdael.milkbeat.plugin.AccountIdentity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** MicroG-RE-TV's package and the account type it registers its Google accounts under. */
internal const val MICROG_PACKAGE = "app.revanced.android.gms"
internal const val MICROG_ACCOUNT_TYPE = "app.revanced"

// The one caller MicroG-RE-TV's TrustedCallerIdentity issues YouTube tokens to.
private const val MILKBEAT_PACKAGE = "nl.neerdael.milkbeat"

// MicroG-RE-TV issues tokens as the official app only for these scopes, and only to Milkbeat.
private const val YOUTUBE_TOKEN_TYPE = "oauth2:https://www.googleapis.com/auth/youtube https://www.googleapis.com/auth/youtube.force-ssl"

// The request option MicroG-RE-TV reads to choose which app the token is issued as.
private const val KEY_IDENTITY = "overridePackage"

// An exported MicroG service in the process that hosts its account authenticator.
private const val MICROG_START_ACTION = "$MICROG_PACKAGE.common.service.START"

private const val MICROG_START_TIMEOUT_MS = 5_000L

// MicroG-RE-TV's release key. Builds signed with it can be updated in place from its releases; other MicroG builds
// under the same package cannot, so only these are offered an update.
private const val MICROG_RE_TV_CERT_SHA256 = "d9ad54b6a83ee6c475c90f51c97c8dc82f1c14cfb540b2f94e81b429d4583b10"

// The first MicroG-RE-TV release that issues Milkbeat its YouTube identities.
private val MICROG_RE_TV_MINIMUM = listOf(7, 2, 1, 5)

/** Whether [versionName] is a numbered release below [minimum]; a name it cannot read is not judged older. */
internal fun versionBelow(
    versionName: String?,
    minimum: List<Int>,
): Boolean {
    val parts = versionName?.split('.')?.map { it.toIntOrNull() ?: return false } ?: return false
    for (index in 0 until maxOf(parts.size, minimum.size)) {
        val part = parts.getOrElse(index) { 0 }
        val required = minimum.getOrElse(index) { 0 }
        if (part != required) return part < required
    }
    return false
}

internal val AccountIdentity.packageName: String
    get() =
        when (this) {
            AccountIdentity.YOUTUBE -> "com.google.android.youtube"
            AccountIdentity.YOUTUBE_MUSIC -> "com.google.android.apps.youtube.music"
        }

sealed interface MicroGTokenResult {
    data class Granted(
        val token: String,
    ) : MicroGTokenResult

    /** MicroG wants the listener to confirm first; [intent] opens its prompt. */
    data class NeedsConsent(
        val intent: Intent,
    ) : MicroGTokenResult
}

/** What Milkbeat needs from Android's account manager for MicroG-RE-TV accounts. Token calls block. */
interface MicroGAccountGateway {
    /** Whether MicroG-RE-TV serves this app at all: it issues YouTube identities to the stable app only. */
    fun serves(): Boolean

    fun installed(): Boolean

    /** When MicroG was last installed or updated, or null when it is not installed. */
    fun installedAt(): Long? = null

    /** Whether the installed MicroG is a MicroG-RE-TV release too old to sign Milkbeat in, which its latest updates. */
    fun outdated(): Boolean

    fun chooseAccountIntent(): Intent

    fun hasAccount(name: String): Boolean

    fun token(
        accountName: String,
        identity: AccountIdentity,
    ): MicroGTokenResult

    fun invalidate(token: String)
}

class AndroidMicroGAccountGateway
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : MicroGAccountGateway {
        private val manager = AccountManager.get(context)

        override fun serves(): Boolean = context.packageName == MILKBEAT_PACKAGE

        override fun installed(): Boolean =
            try {
                context.packageManager.getPackageInfo(MICROG_PACKAGE, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        override fun installedAt(): Long? =
            try {
                context.packageManager.getPackageInfo(MICROG_PACKAGE, 0).lastUpdateTime
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }

        override fun outdated(): Boolean =
            try {
                val info = context.packageManager.getPackageInfo(MICROG_PACKAGE, 0)
                val certificate = mapOf(MICROG_RE_TV_CERT_SHA256.hexToByteArray() to PackageManager.CERT_INPUT_SHA256)
                PackageInfoCompat.hasSignatures(context.packageManager, MICROG_PACKAGE, certificate, false) &&
                    versionBelow(info.versionName, MICROG_RE_TV_MINIMUM)
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }

        // The chooser also makes the picked account visible to Milkbeat, which API 26+ requires.
        // TV firmware can redirect the default chooser to Google Play Services, excluding MicroG.
        override fun chooseAccountIntent(): Intent =
            AccountManager
                .newChooseAccountIntent(null, null, arrayOf(MICROG_ACCOUNT_TYPE), null, null, null, null)
                .apply {
                    if (component ==
                        ComponentName("com.google.android.gms", "com.google.android.gms.common.account.NoTouchAccountPickerActivity")
                    ) {
                        setClassName("android", "android.accounts.ChooseTypeAndAccountActivity")
                    }
                }

        override fun hasAccount(name: String): Boolean = manager.getAccountsByType(MICROG_ACCOUNT_TYPE).any { it.name == name }

        override fun token(
            accountName: String,
            identity: AccountIdentity,
        ): MicroGTokenResult {
            val options = Bundle().apply { putString(KEY_IDENTITY, identity.packageName) }
            val result =
                whileMicroGRuns {
                    manager
                        .getAuthToken(Account(accountName, MICROG_ACCOUNT_TYPE), YOUTUBE_TOKEN_TYPE, options, false, null, null)
                        .result
                }
            result.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }?.let { return MicroGTokenResult.Granted(it) }
            val prompt =
                BundleCompat.getParcelable(result, AccountManager.KEY_INTENT, Intent::class.java)
                    ?: throw AuthenticatorException("MicroG answered with neither a token nor a prompt")
            return MicroGTokenResult.NeedsConsent(prompt)
        }

        override fun invalidate(token: String) = manager.invalidateAuthToken(MICROG_ACCOUNT_TYPE, token)

        // Boot managers such as TCL's refuse to start a stopped MicroG for the account service's authenticator binding,
        // which then fails the token request. A binding from Milkbeat starts it and keeps it running for the request.
        private fun <T> whileMicroGRuns(request: () -> T): T {
            val connected = CountDownLatch(1)
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        service: IBinder,
                    ) = connected.countDown()

                    override fun onNullBinding(name: ComponentName) = connected.countDown()

                    override fun onServiceDisconnected(name: ComponentName) = Unit
                }
            val bound =
                try {
                    context.bindService(Intent(MICROG_START_ACTION).setPackage(MICROG_PACKAGE), connection, Context.BIND_AUTO_CREATE)
                } catch (_: SecurityException) {
                    return request()
                }
            try {
                if (bound) connected.await(MICROG_START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                return request()
            } finally {
                // Android asks for the release whatever the bind returned; a refused bind may have registered nothing.
                try {
                    context.unbindService(connection)
                } catch (_: IllegalArgumentException) {
                }
            }
        }
    }
