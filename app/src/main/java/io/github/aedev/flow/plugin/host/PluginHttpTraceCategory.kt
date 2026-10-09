package io.github.aedev.flow.plugin.host

import io.github.aedev.flow.player.diagnostics.TraceCategory
import okhttp3.HttpUrl

/** Classify only; never return/log URL paths, queries, hostnames, headers or bodies. */
internal fun pluginHttpTraceCategory(url: HttpUrl): TraceCategory {
    val youtube = url.host == "youtube.com" || url.host.endsWith(".youtube.com")
    if (youtube && url.encodedPath.startsWith("/youtubei/")) {
        return when (url.pathSegments.lastOrNull()) {
            "player" -> TraceCategory.YOUTUBE_PLAYER
            "search" -> TraceCategory.YOUTUBE_SEARCH
            "browse" -> TraceCategory.YOUTUBE_BROWSE
            "next" -> TraceCategory.YOUTUBE_NEXT
            else -> TraceCategory.YOUTUBE_API_OTHER
        }
    }
    if (youtube && url.encodedPath.endsWith(".js")) return TraceCategory.PLAYER_SCRIPT
    if (youtube) return TraceCategory.YOUTUBE_PAGE
    if (url.host == "googlevideo.com" || url.host.endsWith(".googlevideo.com")) return TraceCategory.MEDIA_DELIVERY
    return TraceCategory.OTHER_HTTP
}
