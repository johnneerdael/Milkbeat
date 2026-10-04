package io.github.aedev.flow.plugin.mirror

import kotlinx.serialization.Serializable
import nl.neerdael.milkbeat.catalog.EntityRef
import nl.neerdael.milkbeat.catalog.TrackDescriptor
import java.security.MessageDigest

@Serializable
data class MirrorKey(
    val sourcePlugin: String,
    val sourceAccount: String,
    val targetPlugin: String,
    val targetAccount: String,
    val source: EntityRef,
) {
    val id: String get() = digest(listOf(sourcePlugin, sourceAccount, targetPlugin, targetAccount, source.kind.name, source.providerId))
    val sourceKey: String get() = digest(listOf(sourcePlugin, sourceAccount, targetPlugin, source.kind.name, source.providerId))
}

internal fun digest(values: List<String>): String =
    MessageDigest.getInstance("SHA-256").digest(values.joinToString("\u0000").toByteArray()).joinToString("") {
        "%02x".format(it)
    }

@Serializable
data class MirrorMatch(
    val sourcePosition: Int,
    val sourceTrack: TrackDescriptor,
    val destinationTrack: TrackDescriptor,
)

@Serializable
data class MirrorRecord(
    val key: MirrorKey,
    val title: String,
    val revision: String,
    val tracks: List<TrackDescriptor>,
    val matches: List<MirrorMatch> = emptyList(),
    val missed: List<TrackDescriptor> = emptyList(),
    val nextIndex: Int = 0,
    val destination: EntityRef? = null,
    val ready: Boolean = false,
    val artwork: nl.neerdael.milkbeat.catalog.Artwork? = null,
)

data class PlaylistMirrorState(
    val total: Int = 0,
    val matched: Int = 0,
    val missing: Int = 0,
    val ready: Boolean = false,
    val error: String? = null,
    val isPreparing: Boolean = false,
    val phase: MirrorPhase = MirrorPhase.SOURCE_LOADING,
    val phaseCompleted: Int = 0,
    val phaseTotal: Int = 0,
) {
    val percentage: Int get() = mirrorPercentage(this)
}

enum class MirrorPhase { SOURCE_LOADING, MATCHING, WRITING, VERIFYING }

interface MirrorStorage {
    suspend fun get(id: String): MirrorRecord?

    suspend fun put(record: MirrorRecord)
}
