package io.github.aedev.flow.plugin.playback

import nl.neerdael.milkbeat.catalog.TrackDescriptor
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

internal object TrackMatchScore {
    const val POLICY_VERSION = 2
    const val MIN_SCORE = 0.35
    private const val CERTAIN = 1.0
    private const val EARLY_EXIT = 0.95
    private const val MIN_TITLE_SIMILARITY = 0.88
    private const val MIN_ARTIST_SIMILARITY = 0.92
    private const val MAX_DURATION_DELTA_MS = 10_000L
    private const val TITLE_WEIGHT = 0.45
    private const val ARTIST_WEIGHT = 0.35
    private const val DURATION_WEIGHT = 0.20
    private const val UNKNOWN_DURATION = 0.5
    private const val ISRC = "isrc"

    private val bracketedFeature = Regex("[\\[(]\\s*(?:feat(?:uring)?|ft|with)\\b[^)\\]]*[)\\]]", RegexOption.IGNORE_CASE)
    private val trailingFeature = Regex("\\s+(?:feat(?:uring)?|ft)\\.?\\s+.*?(?=\\s*[\\[(]|\\s+[-–—]\\s+|$)", RegexOption.IGNORE_CASE)
    private val featurePrefix = Regex("^\\s*(?:\\(|\\[)?\\s*(?:feat(?:uring)?|ft|with)\\b\\.?\\s*", RegexOption.IGNORE_CASE)
    private const val CATALOG_LABEL =
        "(?:(?:\\d{4}\\s+)?re[- ]?master(?:ed)?(?:\\s+\\d{4})?(?:\\s+version)?|" +
            "clean|explicit|bonus track)"
    private const val VIDEO_LABEL = "(?:official (?:music )?(?:video|audio|visuali[sz]er)|(?:official )?lyrics?|lyric video|music video)"
    private val bracketedDecoration = Regex("[\\[(]\\s*(?:$CATALOG_LABEL|$VIDEO_LABEL)\\s*[)\\]]", RegexOption.IGNORE_CASE)
    private val trailingDecoration = Regex("\\s+[-–—]\\s*(?:$CATALOG_LABEL|$VIDEO_LABEL)\\s*$", RegexOption.IGNORE_CASE)
    private val mixedExcerpt = Regex("\\s*(?:[-–—]\\s*mixed|[\\[(]\\s*mixed[)\\]])\\s*$", RegexOption.IGNORE_CASE)
    private val formatDecoration =
        Regex(
            "[\\[(]\\s*(?:(?:extended|full(?: length)?) (?:mix|edit|version)|radio (?:mix|edit|version))[)\\]]|\\s+[-–—]\\s*(?:(?:extended|full(?: length)?) (?:mix|edit|version)|radio (?:mix|edit|version))\\s*$",
            RegexOption.IGNORE_CASE,
        )
    private val formatPrefix = Regex("\\b(?:extended|full(?: length)?|radio)\\s+(?=(?:mix|remix|edit|version)\\b)")
    private val versionAbbreviation = Regex("\\bver\\b")
    private val versionWord = Regex("\\bversion\\b")
    private val latinAccents = Regex("(?<=\\p{sc=Latin})\\p{M}+")
    private val apostrophes = Regex("['’]")
    private val nonAlphanumeric = Regex("[^\\p{L}\\p{N}\\p{M}\\s]")
    private val spaces = Regex("\\s+")
    private val topicSuffix = Regex("\\s+-\\s+topic$", RegexOption.IGNORE_CASE)
    private val imitationMarkers = Regex("\\b(?:tribute|karaoke|cover|covers|rendition|renditions)\\b")
    private val unavailableArtists = setOf("", "unknown artist", "various artists", "release", "release topic")
    private val variantMarkers =
        Regex(
            "\\b(?:live|en vivo|en directo|ao vivo|acoustic|unplugged|stripped|remix|rework|mashup|dub|mix|edit|vip|" +
                "karaoke|cover|instrumental|piano|demo|session|sped up|spedup|slowed|nightcore|8d|mono|stereo|" +
                "a cappella|acapella|orchestral|orchestra|symphonic|re recording|re recorded|reverb|binaural|solo|alternate|alternative)\\b",
        )
    private val versionAliases =
        listOf(
            Regex("\\b(?:ao vivo|en vivo|en directo)\\b") to "live",
            Regex("\\b(?:akustik|ac[uú]stic[oa]|acoustique)\\b") to "acoustic",
        )

    fun best(
        track: TrackDescriptor,
        candidates: List<TrackDescriptor>,
    ): Scored? {
        candidates.firstOrNull { sharesIsrc(track, it) && !isMixed(it) }?.let { return Scored(it, CERTAIN) }
        var best: Scored? = candidates.firstOrNull { sharesIsrc(track, it) }?.let { Scored(it, CERTAIN) }
        for (candidate in candidates) {
            val evidence = evidence(track, candidate)
            if (evidence.title < MIN_TITLE_SIMILARITY || evidence.artist < MIN_ARTIST_SIMILARITY) continue
            if (!versionsCompatible(recordingTitle(track), recordingTitle(candidate)) || !durationsCompatible(track, candidate)) continue
            if (!collectiveMembersCompatible(track, candidate)) continue
            if (!guestsCompatible(track, candidate)) continue
            val score = evidence.score
            if (score < MIN_SCORE) continue
            if (best == null || (isMixed(best.candidate) && !isMixed(candidate)) ||
                (isMixed(best.candidate) == isMixed(candidate) && score > best.score)
            ) {
                best = Scored(candidate, score)
            }
            if (score >= EARLY_EXIT && !isMixed(candidate)) break
        }
        return best
    }

    fun score(
        track: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Double {
        if (sharesIsrc(track, candidate)) return CERTAIN
        return evidence(track, candidate).score
    }

    internal fun normalize(text: String): String {
        val cleaned =
            text
                .replace(mixedExcerpt, " ")
                .replace(formatDecoration, " ")
                .replace(bracketedFeature, " ")
                .replace(trailingFeature, " ")
                .replace(bracketedDecoration, " ")
                .replace(trailingDecoration, " ")
        val normalized =
            versionAliases.fold(
                normalizeText(cleaned).replace(formatPrefix, "").replace(versionAbbreviation, "version"),
            ) { name, (pattern, canonical) ->
                name.replace(pattern, canonical)
            }
        return if (variantMarkers.containsMatchIn(normalized)) {
            normalized.replace(versionWord, " ").replace(spaces, " ").trim()
        } else {
            normalized
        }
    }

    private fun normalizeText(text: String): String {
        val unicode = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
        val normalized =
            unicode
                .replace("&", " and ")
                .replace(apostrophes, "")
                .replace(nonAlphanumeric, " ")
                .replace(spaces, " ")
                .trim()
        return normalized.ifEmpty { unicode.replace(spaces, " ").trim() }
    }

    private fun foldLatinAccents(text: String): String =
        Normalizer.normalize(Normalizer.normalize(text, Normalizer.Form.NFD).replace(latinAccents, ""), Normalizer.Form.NFC)

    private fun textSimilarity(
        a: String,
        b: String,
    ): Double = maxOf(similarity(a, b), similarity(foldLatinAccents(a), foldLatinAccents(b)))

    private fun evidence(
        track: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Evidence =
        Evidence(
            textSimilarity(recordingTitle(track), recordingTitle(candidate)),
            performerSimilarity(track, candidate),
            durationScore(track.durationMs, candidate.durationMs),
        )

    private fun performerSimilarity(
        track: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Double {
        val source = collectiveMembers(track) ?: listOf(performerCredits(track).firstOrNull().orEmpty())
        val target = collectiveMembers(candidate) ?: listOf(performerCredits(candidate).firstOrNull().orEmpty())
        return source.maxOf { artist -> target.maxOf { artistSimilarity(artist, it) } }
    }

    private fun artistSimilarity(
        artist: String,
        candidate: String,
    ): Double {
        val a = normalizeText(artist.replace(trailingFeature, " ").replace(bracketedFeature, " ").replace(topicSuffix, ""))
        val b = normalizeText(candidate.replace(trailingFeature, " ").replace(bracketedFeature, " ").replace(topicSuffix, ""))
        if (a in unavailableArtists || b in unavailableArtists) return 0.0
        if (!imitationCompatible(a, b)) return 0.0
        return maxOf(textSimilarity(a, b), textSimilarity(a.replace(" ", ""), b.replace(" ", "")))
    }

    private fun imitationCompatible(
        a: String,
        b: String,
    ): Boolean = imitationMarkers.findAll(a).map { it.value }.toSet() == imitationMarkers.findAll(b).map { it.value }.toSet()

    private fun sharesIsrc(
        track: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Boolean {
        val isrc = track.ids[ISRC]
        return !isrc.isNullOrBlank() && isrc.equals(candidate.ids[ISRC], ignoreCase = true)
    }

    private fun guestCredits(
        track: TrackDescriptor,
        collectivePerformers: List<String>,
    ): Set<String> {
        val explicit =
            (listOf(track.title) + track.artists.map { it.name }).flatMap { text ->
                (bracketedFeature.findAll(text) + trailingFeature.findAll(text))
                    .map { match ->
                        match.value.replace(featurePrefix, "").trim(' ', ')', ']')
                    }.toList()
            }
        val title = foldLatinAccents(recordingTitle(track))
        return (performerCredits(track).drop(1) + explicit)
            .map(::normalizeText)
            .filter { it !in unavailableArtists }
            .filterNot { credit -> collectivePerformers.any { artistSimilarity(credit, it) >= MIN_ARTIST_SIMILARITY } }
            .filterNot { credit ->
                Regex("(?:^| )${Regex.escape(foldLatinAccents(credit))} (?:remix|edit|rework)(?: |$)").containsMatchIn(title)
            }.toSet()
    }

    private fun guestsCompatible(
        track: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Boolean {
        val collectivePerformers = collectiveMembers(track).orEmpty() + collectiveMembers(candidate).orEmpty()
        val a = guestCredits(track, collectivePerformers)
        val b = guestCredits(candidate, collectivePerformers)
        if (a.isEmpty() || b.isEmpty()) return true

        fun creditMatches(
            credit: String,
            other: String,
        ): Boolean =
            imitationCompatible(credit, other) &&
                (
                    textSimilarity(credit, other) >= MIN_ARTIST_SIMILARITY ||
                        " $credit ".contains(" $other ") || " $other ".contains(" $credit ")
                )
        return a.all { credit -> b.any { creditMatches(credit, it) } } && b.all { credit -> a.any { creditMatches(credit, it) } }
    }

    private fun versionsCompatible(
        title: String,
        candidate: String,
    ): Boolean {
        val a = normalize(title)
        val b = normalize(candidate)
        val aMarkers = variantMarkers.findAll(a).map { it.value }.toSet() + versionWord.findAll(a).map { it.value }
        val bMarkers = variantMarkers.findAll(b).map { it.value }.toSet() + versionWord.findAll(b).map { it.value }
        if (aMarkers != bMarkers) return false
        return aMarkers.isEmpty() || foldLatinAccents(a) == foldLatinAccents(b)
    }

    private fun isMixed(track: TrackDescriptor): Boolean = mixedExcerpt.containsMatchIn(track.title)

    private fun recordingTitle(track: TrackDescriptor): String {
        val artist = performerCredits(track).firstOrNull().orEmpty()
        val title =
            if (artist.isNotBlank()) {
                track.title.replace(Regex("^${Regex.escape(artist)}\\s*[-–—:]\\s+", RegexOption.IGNORE_CASE), "")
            } else {
                track.title
            }
        return normalize(title)
    }

    private fun collectiveMembers(track: TrackDescriptor): List<String>? {
        val names = track.artists.map { it.name }
        val parts = normalizeText(names.firstOrNull().orEmpty()).split(" and ")
        if (parts.size < 2 || parts.any { it.isBlank() }) return null
        val members =
            parts.map { part ->
                names.drop(1).firstOrNull { name ->
                    foldLatinAccents(normalizeText(name)).endsWith(" $part") ||
                        foldLatinAccents(normalizeText(name)) == part
                } ?: return null
            }
        return members.takeIf { it.distinct().size == parts.size }
    }

    private fun performerCredits(track: TrackDescriptor): List<String> =
        if (collectiveMembers(track) != null) track.artists.drop(1).map { it.name } else track.artists.map { it.name }

    private fun collectiveMembersCompatible(
        track: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Boolean {
        fun present(
            source: TrackDescriptor,
            other: TrackDescriptor,
        ): Boolean =
            collectiveMembers(source)?.all { member ->
                performerCredits(other).any { artistSimilarity(member, it) >= MIN_ARTIST_SIMILARITY }
            } ?: true
        return present(track, candidate) && present(candidate, track)
    }

    private fun durationsCompatible(
        track: TrackDescriptor,
        candidate: TrackDescriptor,
    ): Boolean {
        val trackMs = track.durationMs
        val candidateMs = candidate.durationMs
        return trackMs == null || candidateMs == null || trackMs <= 0 || candidateMs <= 0 ||
            abs(trackMs - candidateMs) <= MAX_DURATION_DELTA_MS ||
            (
                candidateMs > trackMs &&
                    foldLatinAccents(recordingTitle(track)) == foldLatinAccents(recordingTitle(candidate))
            )
    }

    /** The Dice coefficient of the two strings' character bigrams. */
    internal fun similarity(
        a: String,
        b: String,
    ): Double {
        if (a.isBlank() || b.isBlank()) return 0.0
        if (a == b) return CERTAIN
        val bigramsA = bigrams(a)
        val bigramsB = bigrams(b)
        if (bigramsA.isEmpty() || bigramsB.isEmpty()) return 0.0
        return 2.0 * bigramsA.count { it in bigramsB } / (bigramsA.size + bigramsB.size)
    }

    private fun bigrams(text: String): Set<String> = if (text.length < 2) emptySet() else text.windowed(2).toSet()

    internal fun durationScore(
        trackMs: Long?,
        candidateMs: Long?,
    ): Double {
        if (trackMs == null || candidateMs == null || trackMs <= 0 || candidateMs <= 0) return UNKNOWN_DURATION
        val seconds = abs(trackMs / 1000 - candidateMs / 1000)
        return when {
            seconds <= 2 -> 1.0
            seconds <= 5 -> 0.8
            seconds <= 10 -> 0.5
            seconds <= 30 -> 0.2
            else -> 0.0
        }
    }

    private data class Evidence(
        val title: Double,
        val artist: Double,
        val duration: Double,
    ) {
        val score: Double get() = title * TITLE_WEIGHT + artist * ARTIST_WEIGHT + duration * DURATION_WEIGHT
    }

    class Scored(
        val candidate: TrackDescriptor,
        val score: Double,
    )
}
