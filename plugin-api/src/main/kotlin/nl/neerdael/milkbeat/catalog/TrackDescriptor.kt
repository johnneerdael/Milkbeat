package nl.neerdael.milkbeat.catalog

import kotlinx.serialization.Serializable

/**
 * What any provider needs to recognise a track: the metadata plugin that listed it and every audio
 * plugin that may play it read the same description. [ids] holds known ids by id space, e.g.
 * `ytm`, `spotify`, `isrc`, and always includes the describing plugin's own; [ref] is the track in
 * the plugin that described it.
 */
@Serializable
data class TrackDescriptor(
    val ref: EntityRef,
    val title: String,
    val artists: List<ArtistCredit> = emptyList(),
    val album: String? = null,
    val albumRef: EntityRef? = null,
    val durationMs: Long? = null,
    val explicit: Boolean = false,
    val artwork: Artwork? = null,
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val year: Int? = null,
    /** The track has a picture stream, as a music video does. */
    val hasVideo: Boolean = false,
    val ids: Map<String, String> = emptyMap(),
    /** Recording details used to corroborate live-set identity; not the upload date. */
    val description: String? = null,
)

/** Tracks to queue, in order, with the cursor that continues them and the collection they came from. */
@Serializable
data class TrackList(
    val tracks: List<TrackDescriptor>,
    val next: String? = null,
    val source: EntityRef? = null,
    val filters: FilterControl? = null,
    val selectedFilterId: String? = null,
    val revision: String? = null,
)
