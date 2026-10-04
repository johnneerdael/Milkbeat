package io.github.aedev.flow.data.library.index

import java.net.URLDecoder

/** The file references of an M3U or M3U8 playlist, in order; directives and comments are skipped. */
internal fun m3uEntries(text: String): List<String> =
    text
        .removePrefix("\uFEFF")
        .lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .toList()

/**
 * Finds the tracks a playlist names among those indexed in its folder, by folder-relative [paths]
 * ("Techno/Artist - Title.mp3"). A relative entry is read against the playlist's own directory. An
 * absolute one was usually written on another machine, so its longest tail that is a path in the
 * folder wins, and failing that a file name only one track has.
 */
internal class PlaylistResolver(
    paths: Map<String, String>,
) {
    private val byPath = paths.entries.associate { (path, id) -> path.lowercase() to id }
    private val byName =
        paths.entries
            .groupBy({ (path, _) -> path.substringAfterLast('/').lowercase() }, { (_, id) -> id })
            .filterValues { it.size == 1 }
            .mapValues { (_, ids) -> ids.single() }

    fun resolve(
        playlistPath: String,
        entries: List<String>,
    ): List<String> = entries.mapNotNull { resolve(playlistPath, it) }

    private fun resolve(
        playlistPath: String,
        entry: String,
    ): String? {
        val reference = reference(entry) ?: return null
        val segments = reference.split('/').filter(String::isNotEmpty)
        if (!isAbsolute(reference)) {
            val directory = playlistPath.substringBeforeLast('/', "").split('/').filter(String::isNotEmpty)
            normalize(directory + segments)?.let { relative -> byPath[relative.lowercase()]?.let { return it } }
        }
        for (count in segments.size downTo 1) {
            byPath[segments.takeLast(count).joinToString("/").lowercase()]?.let { return it }
        }
        return segments.lastOrNull()?.let { byName[it.lowercase()] }
    }

    private fun reference(entry: String): String? {
        val decoded =
            if (entry.contains("://")) {
                if (!entry.startsWith("file:", ignoreCase = true) && !entry.startsWith("smb:", ignoreCase = true)) return null
                runCatching { URLDecoder.decode(entry.substringAfter("://").replace("+", "%2B"), Charsets.UTF_8.name()) }.getOrNull()
                    ?: return null
            } else {
                entry
            }
        return decoded.replace('\\', '/')
    }

    private fun isAbsolute(reference: String): Boolean = reference.startsWith('/') || DRIVE.containsMatchIn(reference)

    private fun normalize(segments: List<String>): String? {
        val out = ArrayDeque<String>()
        for (segment in segments) {
            when (segment) {
                "." -> Unit
                ".." -> out.removeLastOrNull() ?: return null
                else -> out.addLast(segment)
            }
        }
        return out.joinToString("/")
    }
}

private val DRIVE = Regex("^[A-Za-z]:/")
