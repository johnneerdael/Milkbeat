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
