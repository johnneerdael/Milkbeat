package nl.neerdael.milkbeat.plugin

import kotlinx.serialization.Serializable
import nl.neerdael.milkbeat.catalog.EntityKind

/** The newest plugin API this host implements; a plugin whose [ApiRange.min] is higher cannot run. */
const val PLUGIN_API_VERSION = 10

/** The container format this host reads. */
const val PLUGIN_FORMAT_VERSION = 1

/** `manifest.json` of a `.mbplugin` file: who the plugin is, what it does and what it may reach. */
@Serializable
data class PluginManifest(
    val format: Int,
    val api: ApiRange,
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val description: String? = null,
    val author: Author? = null,
    val updateUrl: String? = null,
    val entry: String = "plugin.js",
    val icon: String? = null,
    val roles: Roles,
    val signIn: List<SignInMethod> = emptyList(),
    val permissions: Permissions = Permissions(),
    val settings: List<SettingDefinition> = emptyList(),
)

@Serializable
data class ApiRange(
    val min: Int,
    val target: Int,
)

@Serializable
data class Author(
    val name: String,
    val url: String? = null,
)

/** What the plugin offers; the host only calls what is declared and hides the rest. */
@Serializable
data class Roles(
    val metadata: MetadataRole? = null,
    val audio: AudioRole? = null,
    val video: VideoRole? = null,
)

@Serializable
enum class MetadataSurface {
    HOME,
    SEARCH,
    SUGGEST,
    ENTITY,
    TRACKS,
    LIBRARY,
    RADIO,
}

@Serializable
data class MetadataRole(
    val surfaces: Set<MetadataSurface>,
    val entities: Set<EntityKind>,
    /** The namespace of this plugin's ids, e.g. `ytm`; a track's [nl.neerdael.milkbeat.catalog.TrackDescriptor.ids] key. */
    val idSpace: String,
    val personalCollections: Boolean = false,
    val privatePlaylistImport: Boolean = false,
)

@Serializable
data class AudioRole(
    /** Id spaces this plugin resolves directly, without matching. */
    val idSpaces: Set<String>,
    /** Whether the plugin can find its own version of a track described by another plugin (`audio.match`). */
    val match: Boolean = false,
    val radio: Boolean = false,
    val musicVideo: Boolean = false,
    val reportPlayback: Boolean = false,
    /** How the plugin's streams arrive, so the player picks the matching source before resolving one. */
    val delivery: AudioDelivery = AudioDelivery.PROGRESSIVE,
    val batchMatching: Boolean = false,
)

@Serializable
enum class AudioDelivery {
    /** One file, read by byte range. */
    PROGRESSIVE,

    /** An HLS playlist, whose segments and keys are fetched from the playlist's own URLs. */
    HLS,
}

@Serializable
enum class VideoSurface {
    SEARCH,
    SUGGEST,
    CHANNEL,
    PLAYLIST,
    RELATED,
    COMMENTS,
    LIVE_CHAT,
}

@Serializable
data class VideoRole(
    val idSpace: String,
    val surfaces: Set<VideoSurface>,
    val live: Boolean = false,
    val reportPlayback: Boolean = false,
)

/**
 * What the plugin may reach. [network] and [browser] are host patterns (`music.youtube.com`,
 * `*.googlevideo.com`); [storage] is a byte quota for its key/value store.
 */
@Serializable
data class Permissions(
    val network: List<String> = emptyList(),
    val browser: List<String> = emptyList(),
    val storage: Long = 1_048_576,
)

@Serializable
enum class SettingType {
    TOGGLE,
    CHOICE,
    TEXT,
}

/** A setting the host renders for the plugin; [options] of a [SettingType.CHOICE] may be `dynamic`. */
@Serializable
data class SettingDefinition(
    val key: String,
    val type: SettingType,
    val label: String,
    val description: String? = null,
    val default: String? = null,
    val options: List<SettingOption> = emptyList(),
    val dynamicOptions: Boolean = false,
)

@Serializable
data class SettingOption(
    val value: String,
    val label: String,
)
