package io.github.aedev.flow.utils

import io.github.aedev.flow.data.localmedia.LocalMediaIds

object ThumbnailUrlResolver {
    private val youtubeVideoThumbnailPattern =
        Regex("""(?:https?:)?//(?:i\d*\.ytimg\.com|img\.youtube\.com)/(?:vi|vi_webp)/([^/?#]+)/[^/?#]+""")
    private val googleCdnSizePattern = Regex("""w\d+-h\d+""")
    private val googleCdnParamStartPattern = Regex("""=(?:w|s|h)""")

    // SoundCloud serves each artwork and avatar under named sizes. The 1080 px cover also feeds session
    // metadata, so it stays at t1080x1080; the full-screen view gets t3000x3000 from the playback stream.
    private val soundCloudSizePattern =
        Regex(
            """^(https://[a-z0-9-]+\.sndcdn\.com/.+)-(?:large|crop|mini|tiny|small|badge|original|t\d+x\d+)(\.[a-z]+)$""",
            RegexOption.IGNORE_CASE,
        )

    fun buildHighQualityYoutubeThumbnail(videoId: String): String {
        val id = videoId.trim()
        return if (id.isEmpty() || LocalMediaIds.isLocal(id)) "" else "https://i.ytimg.com/vi/$id/hq720.jpg"
    }

    fun buildMaxResYoutubeThumbnail(videoId: String): String {
        val id = videoId.trim()
        return if (id.isEmpty()) "" else "https://i.ytimg.com/vi/$id/maxresdefault.jpg"
    }

    /**
     * Candidate tiers for a feed/list/grid card, best first.
     *
     * maxresdefault is deliberately excluded: YouTube only generates it for a subset of videos,
     * so requesting it from a card costs a failed round trip before the fallback on every video
     * that lacks it, and when it does exist it is 1920x1080 for a surface that never shows more
     * than roughly a third of those pixels. hq720 is already >= the widest phone card.
     */
    fun youtubeThumbnailCandidates(videoId: String): List<String> {
        val id = videoId.trim()
        if (id.isEmpty()) return emptyList()
        return listOf(
            "https://i.ytimg.com/vi/$id/hq720.jpg",
            "https://i.ytimg.com/vi/$id/hqdefault.jpg",
        )
    }

    fun resolveVideoThumbnailCandidates(
        videoId: String,
        rawUrl: String?,
    ): List<String> {
        val raw = rawUrl?.trim().orEmpty()
        val resolvedVideoId = resolveYoutubeThumbnailVideoId(videoId, raw)
        val youtubeCandidates = youtubeThumbnailCandidates(resolvedVideoId)

        val candidates =
            when {
                raw.isEmpty() -> youtubeCandidates
                isYoutubeVideoThumbnail(raw) -> youtubeCandidates
                else -> listOf(raw) + youtubeCandidates
            }

        return candidates
            .filter { it.isNotBlank() }
            .distinct()
    }

    fun normalizeVideoThumbnail(
        videoId: String,
        rawUrl: String?,
    ): String {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty()) return buildHighQualityYoutubeThumbnail(videoId)

        if (!youtubeVideoThumbnailPattern.containsMatchIn(raw)) return raw
        val resolvedVideoId = resolveYoutubeThumbnailVideoId(videoId, raw)
        if (raw.contains("maxresdefault", ignoreCase = true)) {
            return buildMaxResYoutubeThumbnail(resolvedVideoId).ifEmpty { raw }
        }

        return buildHighQualityYoutubeThumbnail(resolvedVideoId).ifEmpty { raw }
    }

    fun resolveMusicThumbnail(
        videoId: String,
        rawUrl: String?,
        size: Int = 1080,
    ): String {
        val raw = rawUrl?.trim().orEmpty()
        val id = videoId.trim()

        if (raw.isEmpty()) return buildHighQualityYoutubeThumbnail(id)

        return when {
            isYoutubeVideoThumbnail(raw) -> {
                normalizeVideoThumbnail(id, raw)
            }

            raw.contains("googleusercontent.com") || raw.contains("ggpht.com") || soundCloudSizePattern.matches(raw) -> {
                resizeImageThumbnail(raw, size, size)
            }

            else -> {
                raw
            }
        }
    }

    fun isYoutubeVideoThumbnail(rawUrl: String?): Boolean {
        val raw = rawUrl?.trim().orEmpty()
        return youtubeVideoThumbnailPattern.containsMatchIn(raw)
    }

    private fun resolveYoutubeThumbnailVideoId(
        videoId: String,
        rawUrl: String,
    ): String =
        youtubeVideoThumbnailPattern
            .find(rawUrl)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: videoId.trim()

    fun resizeImageThumbnail(
        rawUrl: String?,
        width: Int? = null,
        height: Int? = null,
    ): String {
        val raw = rawUrl?.trim().orEmpty()
        if (raw.isEmpty() || (width == null && height == null)) return raw

        val isGoogleCdn = raw.contains("googleusercontent.com") || raw.contains("ggpht.com")
        val isYtimg = raw.contains("i.ytimg.com") || raw.contains("img.youtube.com")

        return when {
            isGoogleCdn -> resizeGoogleCdnThumbnail(raw, width, height)
            isYtimg -> resizeYoutubeThumbnail(raw, width ?: height ?: 0)
            else -> resizeSoundCloudArtwork(raw, maxOf(width ?: 0, height ?: 0))
        }
    }

    private fun resizeSoundCloudArtwork(
        rawUrl: String,
        size: Int,
    ): String {
        val match = soundCloudSizePattern.matchEntire(rawUrl) ?: return rawUrl
        val name =
            when {
                size > 500 -> "t1080x1080"
                size > 300 -> "t500x500"
                size > 100 -> "t300x300"
                else -> "large"
            }
        return "${match.groupValues[1]}-$name${match.groupValues[2]}"
    }

    private fun resizeGoogleCdnThumbnail(
        rawUrl: String,
        width: Int?,
        height: Int?,
    ): String {
        val w = width ?: height ?: return rawUrl
        val h = height ?: width ?: return rawUrl

        if (googleCdnSizePattern.containsMatchIn(rawUrl)) {
            return rawUrl.replace(googleCdnSizePattern, "w$w-h$h")
        }

        val paramStart = googleCdnParamStartPattern.find(rawUrl)?.range?.first
        val baseUrl = if (paramStart != null) rawUrl.substring(0, paramStart) else rawUrl

        return if (width != null && height != null) {
            "$baseUrl=w$w-h$h-p-l90-rj"
        } else {
            "$baseUrl=s$w-p-l90-rj"
        }
    }

    private fun resizeYoutubeThumbnail(
        rawUrl: String,
        width: Int,
    ): String =
        when {
            width > 480 -> {
                rawUrl
                    .replace("mqdefault.jpg", "hq720.jpg")
                    .replace("hqdefault.jpg", "hq720.jpg")
                    .replace("sddefault.jpg", "hq720.jpg")
                    .replace("default.jpg", "hq720.jpg")
                    .replace("mqdefault.webp", "hq720.jpg")
                    .replace("hqdefault.webp", "hq720.jpg")
                    .replace("sddefault.webp", "hq720.jpg")
                    .replace("default.webp", "hq720.jpg")
            }

            width > 320 -> {
                rawUrl
                    .replace("mqdefault.jpg", "hqdefault.jpg")
                    .replace("default.jpg", "hqdefault.jpg")
                    .replace("mqdefault.webp", "hqdefault.jpg")
                    .replace("default.webp", "hqdefault.jpg")
            }

            else -> {
                rawUrl
            }
        }
}
