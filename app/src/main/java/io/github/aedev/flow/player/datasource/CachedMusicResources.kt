package io.github.aedev.flow.player.datasource

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import nl.neerdael.milkbeat.plugin.PluginJson

@OptIn(UnstableApi::class)
internal fun hasCompleteMusicDownload(
    cache: Cache,
    id: String,
    completedLength: Long = -1,
): Boolean {
    val metadataLength = ContentMetadata.getContentLength(cache.getContentMetadata(id))
    val length = if (metadataLength > 0) metadataLength else completedLength
    return length > 0 && cache.isCached(id, 0, length)
}

@OptIn(UnstableApi::class)
internal fun bindCachedMusicRendition(
    cache: Cache,
    id: String,
    rendition: String,
) {
    val previous = cache.getContentMetadata(id).get(RENDITION_KEY, "")
    if (previous != rendition) {
        cache.removeResource(id)
        cache.applyContentMetadataMutations(id, ContentMetadataMutations().set(RENDITION_KEY, rendition))
    }
}

private const val RENDITION_KEY = "milkbeat.rendition"

@OptIn(UnstableApi::class)
internal fun bindAdaptiveMusicRenditions(
    cache: Cache,
    id: String,
    keys: List<String>,
) = synchronized(cache) {
    val all = (adaptiveMusicRenditions(cache, id) + keys).distinct()
    val retained = all.takeLast(MAX_ADAPTIVE_RENDITIONS)
    (all - retained.toSet()).forEach(cache::removeResource)
    cache.applyContentMetadataMutations(
        id,
        ContentMetadataMutations().set(
            ADAPTIVE_RENDITIONS_KEY,
            PluginJson.encodeToString(ListSerializer(String.serializer()), retained),
        ),
    )
}

@OptIn(UnstableApi::class)
internal fun clearCachedMusicResources(
    cache: Cache,
    id: String,
) = synchronized(cache) {
    adaptiveMusicRenditions(cache, id).forEach(cache::removeResource)
    cache.removeResource(id)
}

@OptIn(UnstableApi::class)
private fun adaptiveMusicRenditions(
    cache: Cache,
    id: String,
): List<String> {
    val encoded = cache.getContentMetadata(id).get(ADAPTIVE_RENDITIONS_KEY, "").orEmpty()
    return if (encoded.isEmpty()) emptyList() else PluginJson.decodeFromString(ListSerializer(String.serializer()), encoded)
}

private const val ADAPTIVE_RENDITIONS_KEY = "milkbeat.adaptive-renditions"
private const val MAX_ADAPTIVE_RENDITIONS = 32
