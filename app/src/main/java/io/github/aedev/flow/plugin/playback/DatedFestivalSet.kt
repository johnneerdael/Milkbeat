package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.catalog.TrackDescriptor
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

internal object DatedFestivalSet {
    private val festival = Regex("\\b(?:tml|tomorrowland)\\b")
    private val editions = Regex("\\b(?:winter|brasil|brazil)\\b")
    private val liveSet = Regex("\\b(?:live|recorded|set|mix|festival)\\b")
    private val recording = Regex("\\b(?:recorded|performed|live (?:at|from)|set (?:at|from))\\b")
    private val variants = Regex("\\b(?:tribute|cover|karaoke|remix|rework|mashup|sped|slowed)\\b")
    private val credit = Regex("^(.+?)\\s*[-–—|@•:]\\s*(.+)$")
    private val date =
        Regex(
            "(?<!\\d)(?:(\\d{1,2})[-./](\\d{1,2})[-./]((?:19|20)\\d{2})|" +
                "((?:19|20)\\d{2})-(\\d{1,2})-(\\d{1,2}))(?!\\d)",
        )
    private val year = Regex("\\b(?:19|20)\\d{2}\\b")

    // Null leaves ordinary recordings to the existing matcher. A dated festival set needs its day,
    // rather than just a similar title: festivals host multiple performances by the same artist.
    fun decision(
        source: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Boolean? {
        val sourceMs = source.durationMs ?: return null
        if (sourceMs < 20 * 60 * 1000L) return null
        val sourceTitle = source.title.lowercase(Locale.ROOT)
        if (!festival.containsMatchIn(sourceTitle) || !liveSet.containsMatchIn(sourceTitle)) return null
        val sourceDates = recordingDates(source)
        if (sourceDates.isEmpty()) return null
        val candidateMs = candidate.durationMs ?: return false
        if (candidateMs < 20 * 60 * 1000L || kotlin.math.abs(sourceMs - candidateMs) > sourceMs * 0.15) return false
        val candidateTitle = candidate.title.lowercase(Locale.ROOT)
        if (!festival.containsMatchIn(candidateTitle) || !liveSet.containsMatchIn(candidateTitle)) return false
        if (sourceDates.size != 1 || recordingDates(candidate) != sourceDates) return false
        if (edition(sourceTitle) != edition(candidateTitle)) return false
        val recordingYear = sourceDates.single().year.toString()
        if ((year.findAll(sourceTitle) + year.findAll(candidateTitle)).any { it.value != recordingYear }) return false
        if (variants.containsMatchIn(sourceTitle) || variants.containsMatchIn(candidateTitle)) return false
        val sourcePerformer = performer(source) ?: return false
        val targetPerformer = performer(candidate) ?: return false
        return sourcePerformer == targetPerformer
    }

    private fun edition(title: String): Set<String> =
        editions.findAll(title).map { if (it.value == "brasil") "brazil" else it.value }.toSet()

    private fun performer(track: TrackDescriptor): String? {
        val prefix = credit.matchEntire(track.title)?.groupValues?.get(1)
        val name =
            prefix ?: track.artists
                .firstOrNull()
                ?.name
                ?.takeIf { track.title.startsWith(it, ignoreCase = true) } ?: return null
        return TrackMatchScore.normalize(name).takeIf { it.isNotBlank() && !variants.containsMatchIn(it) }
    }

    private fun recordingDates(track: TrackDescriptor): Set<LocalDate> {
        val description = track.description.orEmpty().lowercase(Locale.ROOT)
        val recordingLines =
            description
                .split(Regex("\\n|\\.\\s+"))
                .filter { festival.containsMatchIn(it) && recording.containsMatchIn(it) }
        return (listOf(track.title) + recordingLines)
            .flatMap { text ->
                date
                    .findAll(text)
                    .mapNotNull { match ->
                        val groups = match.groupValues
                        val parts =
                            if (groups[3].isNotEmpty()) {
                                listOf(groups[3], groups[2], groups[1])
                            } else {
                                listOf(groups[4], groups[5], groups[6])
                            }
                        try {
                            LocalDate.of(parts[0].toInt(), parts[1].toInt(), parts[2].toInt())
                        } catch (_: DateTimeException) {
                            null
                        }
                    }.toList()
            }.toSet()
    }
}
