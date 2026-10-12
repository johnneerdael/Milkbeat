package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import nl.neerdael.milkbeat.catalog.ProviderAccount

/** A way to sign in that the host knows how to run. Web login remains supported alongside device-code pairing. */
@Serializable
sealed interface SignInMethod {
    val id: String
    val label: String
}

/**
 * A sign-in the host runs in its own web view, driven from the listener's phone: it opens
 * [startUrl], waits until a page under [successUrlPrefix] has loaded with every cookie in
 * [requiredCookies] set for [cookieUrl], runs [extractScript] there (it must evaluate to a JSON object
 * of strings, or a promise of one), then hands the result to the plugin as a [WebLoginResult] and
 * forgets it.
 *
 * A site whose API tokens are short-lived and minted by its own pages sets [refreshUrl], a page on the
 * same site as [cookieUrl]: the plugin then calls `signIn.refresh` with the cookies it keeps, and the
 * host opens that page in a web view of its own holding only those cookies, runs [extractScript] again
 * and answers with the fresh values and cookies. The host keeps nothing between calls.
 */
@Serializable
@SerialName("webLogin")
data class WebLoginMethod(
    override val id: String,
    override val label: String,
    val startUrl: String,
    val successUrlPrefix: String,
    val cookieUrl: String,
    val requiredCookies: List<String>,
    val extractScript: String? = null,
    val refreshUrl: String? = null,
    /** Runs after a sign-in document loads, for provider-specific page compatibility fixes. */
    val pageScript: String? = null,
) : SignInMethod

/** Runs [method]'s [WebLoginMethod.refreshUrl] again with the [cookies] the plugin was handed. */
@Serializable
data class WebLoginRefreshRequest(
    val method: String,
    val cookies: String,
)

@Serializable
data class WebLoginResult(
    val method: String,
    val cookies: String,
    val extracted: Map<String, String> = emptyMap(),
)

/** The plugin creates a challenge; the host displays it and polls only while its screen is visible. */
@Serializable
@SerialName("deviceCode")
data class DeviceCodeMethod(
    override val id: String,
    override val label: String,
) : SignInMethod

@Serializable
data class DeviceCodeBeginRequest(
    val method: String,
)

/** An opaque plugin-owned handle, never an account token. */
@Serializable
data class DeviceCodeSession(
    val session: String,
)

@Serializable
data class DeviceCodeChallenge(
    val session: String,
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String? = null,
    val intervalMs: Long,
    val expiresInMs: Long? = null,
    val message: String? = null,
)

@Serializable
enum class DeviceCodeStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("signedIn")
    SIGNED_IN,

    @SerialName("expired")
    EXPIRED,

    @SerialName("denied")
    DENIED,
}

@Serializable
data class DeviceCodePollResult(
    val status: DeviceCodeStatus,
    val account: ProviderAccount? = null,
    /** The provider may increase the minimum delay after a pending response. */
    val intervalMs: Long? = null,
)

/**
 * A sign-in with a Google account already on the device in MicroG-RE-TV. The host lets the listener
 * pick the account and confirm, in MicroG's own prompt, each of [identities]; afterwards the plugin
 * asks the host for an access token per identity with `signIn.token`. The host fixes the account
 * type and the scopes, so a plugin can only name which of the supported apps a token is issued as.
 */
@Serializable
@SerialName("androidAccount")
data class AndroidAccountMethod(
    override val id: String,
    override val label: String,
    val identities: List<AccountIdentity>,
) : SignInMethod

/** The official app a token is issued as; each is confirmed separately by the listener. */
@Serializable
enum class AccountIdentity {
    @SerialName("youtube")
    YOUTUBE,

    @SerialName("youtubeMusic")
    YOUTUBE_MUSIC,
}

/** The account the listener chose, handed to the plugin once every identity is confirmed. */
@Serializable
data class AndroidAccountSignIn(
    val method: String,
    val accountName: String,
)

@Serializable
data class AccountTokenRequest(
    val method: String,
    val identity: AccountIdentity,
)

/** A short-lived access token; the host keeps none and the plugin keeps it only in memory. */
@Serializable
data class AccountToken(
    val token: String,
)

/** A token the provider refused, so the next `signIn.token` mints a fresh one. */
@Serializable
data class AccountTokenInvalidation(
    val method: String,
    val token: String,
)
