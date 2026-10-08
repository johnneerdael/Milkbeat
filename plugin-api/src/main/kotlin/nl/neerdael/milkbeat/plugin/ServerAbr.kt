package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.Serializable

/** A server-driven adaptive presentation, consumed by the host's media transport. */
@Serializable
data class ServerAbrPlayback(
    val url: String,
    val videoId: String,
    val config: String,
    val client: ServerAbrClientInfo,
    val formats: List<ServerAbrFormat>,
    val poToken: String? = null,
    val visitorCookie: String? = null,
    val durationMs: Long? = null,
    val live: Boolean = false,
)

/** The client identity sent with the presentation's adaptive requests. */
@Serializable
data class ServerAbrClientInfo(
    val clientName: Int,
    val clientVersion: String,
    val deviceMake: String? = null,
    val deviceModel: String? = null,
    val osName: String? = null,
    val osVersion: String? = null,
    val hl: String? = null,
    val gl: String? = null,
    val utcOffsetMinutes: Long? = null,
)

/** The discriminator must survive JavaScript without rounding uint64 last-modified values. */
@Serializable
data class ServerAbrFormat(
    val format: MediaFormat,
    val itag: Int,
    val lastModified: String,
    val xTags: String? = null,
)
