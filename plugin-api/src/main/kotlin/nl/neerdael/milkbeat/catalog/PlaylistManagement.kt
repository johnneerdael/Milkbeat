package nl.neerdael.milkbeat.catalog

import kotlinx.serialization.Serializable

@Serializable
data class PersonalCollectionsRequest(
    val cursor: String? = null,
    val expectedAccountKey: String? = null,
)

@Serializable
enum class PersonalCollectionKind { OWNED_PLAYLIST, LIKED_SONGS }

@Serializable
data class PersonalCollection(
    val ref: EntityRef,
    val title: String,
    val kind: PersonalCollectionKind,
    val revision: String? = null,
    val trackCount: Int? = null,
    val artwork: Artwork? = null,
)

@Serializable
data class PersonalCollectionsPage(
    val collections: List<PersonalCollection>,
    val next: String? = null,
)

@Serializable
enum class PrivatePlaylistImportMode { REPLACE, ENSURE, APPEND }

@Serializable
data class PlaylistArtwork(
    val dataBase64: String,
    val mimeType: String,
    val sizeBytes: Int,
)

@Serializable
data class PrivatePlaylistImportRequest(
    val sourceKey: String,
    val title: String,
    val tracks: List<EntityRef>,
    val target: EntityRef? = null,
    val expectedAccountKey: String? = null,
    val cursor: String? = null,
    val mode: PrivatePlaylistImportMode = PrivatePlaylistImportMode.REPLACE,
    val startIndex: Int? = null,
    val artwork: PlaylistArtwork? = null,
)

@Serializable
data class PrivatePlaylistImportResult(
    val ref: EntityRef? = null,
    val next: String? = null,
    val retryAfterMs: Long? = null,
    val progress: PrivatePlaylistImportProgress? = null,
)

@Serializable
enum class PrivatePlaylistImportPhase { PREPARING, WRITING, VERIFYING }

@Serializable
data class PrivatePlaylistImportProgress(
    val phase: PrivatePlaylistImportPhase,
    val completed: Int,
    val total: Int,
)
