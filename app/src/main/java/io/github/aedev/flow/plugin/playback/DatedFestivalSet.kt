package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.catalog.TrackDescriptor
import java.time.DateTimeException
import java.time.LocalDate
import java.util.Locale

internal object DatedFestivalSet {
    private val festival = Regex("\\b(?:tml|tomorrowland)\\b")
    private val editions = Regex("\\b(?:tml|tomorrowland)\\s+(winter|brasil|brazil)\\b")
    private val liveSet = Regex("\\b(?:live|recorded|set|mix|festival)\\b")
    private val recording = Regex("\\b(?:recorded|performed|live (?:at|from)|set (?:at|from))\\b", RegexOption.IGNORE_CASE)
    private val credit = Regex("^(.+?)\\s*[-–—|@•:]\\s+(.+)$")
    private val date =
        Regex(
            "(?<!\\d)(?:(\\d{1,2})[-./](\\d{1,2})[-./]((?:19|20)\\d{2})|" +
                "((?:19|20)\\d{2})-(\\d{1,2})-(\\d{1,2}))(?!\\d)",
        )
    private val year = Regex("\\b(?:tml|tomorrowland)(?:\\s+(?:winter|brasil|brazil|festival))?\\s+((?:19|20)\\d{2})\\b")
    private val publication =
        Regex(
            "\\b(?:upload(?:ed)?|publish(?:ed)?|publication|posted|released|release date|premier(?:e|ed))\\b",
            RegexOption.IGNORE_CASE,
        )
    private val publicationAfter =
        Regex(
            "^\\s*(?:[-–—:]\\s*)?(?:upload|publication|release|premiere)\\s+date\\b",
            RegexOption.IGNORE_CASE,
        )

    // Null leaves ordinary recordings to the existing matcher. A dated festival set needs its day,
    // rather than just a similar title: festivals host multiple performances by the same artist.
    fun decision(
        source: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Boolean? {
        val sourceMs = source.durationMs ?: return null
        if (sourceMs < 20 * 60 * 1000L) return null
        val sourceTitle = eventTitle(source).lowercase(Locale.ROOT)
        if (!festival.containsMatchIn(sourceTitle) || !liveSet.containsMatchIn(sourceTitle)) return null
        val sourceDates = recordingDates(source)
        if (sourceDates.isEmpty()) return null
        val candidateMs = candidate.durationMs ?: return false
        if (candidateMs < 20 * 60 * 1000L || kotlin.math.abs(sourceMs - candidateMs) > sourceMs * 0.15) return false
        val candidateTitle = eventTitle(candidate).lowercase(Locale.ROOT)
        if (!festival.containsMatchIn(candidateTitle) || !liveSet.containsMatchIn(candidateTitle)) return false
        if (sourceDates.size != 1 || recordingDates(candidate) != sourceDates) return false
        if (edition(sourceTitle) != edition(candidateTitle)) return false
        val recordingYear = sourceDates.single().year.toString()
        if ((year.findAll(recordingTitle(sourceTitle)) + year.findAll(recordingTitle(candidateTitle))).any {
                it.groupValues[1] != recordingYear
            }
        ) {
            return false
        }
        if (!TrackMatchScore.liveSetVersionsCompatible(sourceTitle, candidateTitle)) return false
        val sourcePerformer = performer(source) ?: return null
        val targetPerformer = performer(candidate, sourcePerformer) ?: return null
        return TrackMatchScore.artistSimilarity(sourcePerformer, targetPerformer) == 1.0
    }

    private fun edition(title: String): Set<String> =
        editions.findAll(title).map { if (it.groupValues[1] == "brasil") "brazil" else it.groupValues[1] }.toSet()

    internal fun performer(
        track: TrackDescriptor,
        expected: String? = null,
    ): String? {
        val title = expected?.let { TrackMatchScore.presentedTitle(track.title, it, credit) } ?: track.title
        val prefix = credit.matchEntire(title)?.groupValues?.get(1)
        val name =
            prefix ?: track.artists
                .firstOrNull()
                ?.name
                ?.takeIf { title.startsWith("$it ", ignoreCase = true) } ?: return null
        return TrackMatchScore.foldLatinAccents(TrackMatchScore.normalize(name)).takeIf { it.isNotBlank() }
    }

    private fun eventTitle(track: TrackDescriptor): String = credit.matchEntire(track.title)?.groupValues?.get(2) ?: track.title

    private fun publicationDate(
        text: String,
        match: MatchResult,
    ): Boolean {
        val prefix = text.substring(0, match.range.first)
        val published =
            publication
                .findAll(prefix)
                .lastOrNull()
                ?.range
                ?.first ?: -1
        val recorded =
            recording
                .findAll(prefix)
                .lastOrNull()
                ?.range
                ?.first ?: -1
        return published > recorded || publicationAfter.containsMatchIn(text.substring(match.range.last + 1))
    }

    private fun recordingTitle(title: String): String = date.replace(title) { if (publicationDate(title, it)) " " else it.value }

    private fun recordingDates(track: TrackDescriptor): Set<LocalDate> {
        val description = track.description.orEmpty().lowercase(Locale.ROOT)
        val recordingLines =
            description
                .split(Regex("\\n|\\.\\s+"))
                .filter { festival.containsMatchIn(it) && recording.containsMatchIn(it) }
        return (listOf(eventTitle(track)) + recordingLines)
            .flatMap { text ->
                date
                    .findAll(text)
                    .mapNotNull { match ->
                        if (publicationDate(text, match)) return@mapNotNull null
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
