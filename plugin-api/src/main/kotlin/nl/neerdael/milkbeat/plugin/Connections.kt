package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.Serializable

/** One plugin-owned external connection, listed for the host's connection settings. */
@Serializable
data class PluginConnectionRow(
    val id: String,
    val title: String,
    val host: String,
    val addonId: String? = null,
    val enabled: Boolean = true,
)

/**
 * A link the listener reviewed: the host fetched the manifest itself (no credentials, public HTTPS
 * origin) and showed its provider and destination before the listener approved it. [useAccount]
 * says the plugin may send account-derived tokens to this add-on; the plugin decides what that
 * means for its protocol.
 */
@Serializable
data class PluginConnectionSave(
    val id: String? = null,
    val url: String,
    val addonId: String,
    val useAccount: Boolean = false,
)

@Serializable
data class PluginConnectionSaved(
    val id: String,
)

@Serializable
data class PluginConnectionRemove(
    val id: String,
)

@Serializable
data class PluginConnectionEnabled(
    val id: String,
    val enabled: Boolean,
)

/** What a connection's test access check concluded, for the host to phrase on the TV. */
@Serializable
enum class PluginConnectionTestState {
    /** The manifest and a track lookup both answered. */
    VERIFIED,

    /** The manifest answered but no track could be looked up. */
    LOOKUP_FAILED,

    /** The manifest answered but the account was not accepted for this add-on. */
    UNAUTHORIZED,

    /** The manifest could not be loaded at all. */
    MANIFEST_FAILED,

    /** The connection needs a sign-in first. */
    SIGNED_OUT,
}

@Serializable
data class PluginConnectionTest(
    val id: String,
)

@Serializable
data class PluginConnectionTestResult(
    val state: PluginConnectionTestState,
    /** Optional provider detail already safe to display, such as the resolved add-on name. */
    val detail: String? = null,
)

/** A manifest the host fetched without credentials so the plugin can review an unapproved origin. */
@Serializable
data class ConnectionInspectRequest(
    val url: String,
)

@Serializable
data class ConnectionManifest(
    /** The normalized manifest URL the host actually fetched; redirects are not followed. */
    val url: String,
    val id: String,
    val name: String? = null,
    val resources: List<String> = emptyList(),
    val drmSystems: List<String> = emptyList(),
)
