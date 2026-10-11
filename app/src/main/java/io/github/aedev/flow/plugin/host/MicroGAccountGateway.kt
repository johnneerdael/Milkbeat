package io.github.aedev.flow.plugin.host

import android.accounts.Account
import android.accounts.AccountManager
import android.accounts.AuthenticatorException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.os.BundleCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import nl.neerdael.milkbeat.plugin.AccountIdentity
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

        // The chooser also makes the picked account visible to Milkbeat, which API 26+ requires.
        override fun chooseAccountIntent(): Intent =
            AccountManager.newChooseAccountIntent(null, null, arrayOf(MICROG_ACCOUNT_TYPE), null, null, null, null)

        override fun hasAccount(name: String): Boolean = manager.getAccountsByType(MICROG_ACCOUNT_TYPE).any { it.name == name }

        override fun token(
            accountName: String,
            identity: AccountIdentity,
        ): MicroGTokenResult {
            val options = Bundle().apply { putString(KEY_IDENTITY, identity.packageName) }
            val result =
                manager
                    .getAuthToken(Account(accountName, MICROG_ACCOUNT_TYPE), YOUTUBE_TOKEN_TYPE, options, false, null, null)
                    .result
            result.getString(AccountManager.KEY_AUTHTOKEN)?.takeIf { it.isNotBlank() }?.let { return MicroGTokenResult.Granted(it) }
            val prompt =
                BundleCompat.getParcelable(result, AccountManager.KEY_INTENT, Intent::class.java)
                    ?: throw AuthenticatorException("MicroG answered with neither a token nor a prompt")
            return MicroGTokenResult.NeedsConsent(prompt)
        }

        override fun invalidate(token: String) = manager.invalidateAuthToken(MICROG_ACCOUNT_TYPE, token)
    }
