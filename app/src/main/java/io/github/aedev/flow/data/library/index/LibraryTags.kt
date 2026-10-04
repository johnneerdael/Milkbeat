package io.github.aedev.flow.data.library.index

import androidx.annotation.OptIn
import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.extractor.metadata.vorbis.VorbisComment
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import kotlin.math.roundToInt

/** What the index keeps of one file's tags. Files are tagged unevenly, so every field may be empty. */
internal data class LibraryTags(
    val title: String = "",
    val artists: List<String> = emptyList(),
    val album: String = "",
    val albumArtists: List<String> = emptyList(),
    val genre: String = "",
    val label: String = "",
    val catalogNumber: String = "",
    val year: Int? = null,
    val releaseId: String = "",
    val trackNumber: Int? = null,
    val discNumber: Int? = null,
    val bpm: Int? = null,
    val musicalKey: String = "",
    val energy: Int? = null,
    val taggedAtMs: Long? = null,
    val lengthMs: Long? = null,
)

/**
 * Reads the tags of a file from its ID3 frames or Vorbis comments, under the names Mp3tag shows.
 * A credit holds one artist per stored value, and a value naming several is split on commas only:
 * Beatport writes "Arma, Tag & Wandrach" for two acts, so `&` stays inside a name.
 */
@OptIn(UnstableApi::class)
internal fun libraryTags(
    entries: List<Metadata.Entry>,
    zone: ZoneId = ZoneId.systemDefault(),
): LibraryTags {
    val fields = LinkedHashMap<String, MutableList<String>>()
    for (entry in entries) {
        when (entry) {
            is TextInformationFrame -> {
                val name = if (entry.id == "TXXX" || entry.id == "TXX") entry.description.orEmpty().uppercase() else ID3_NAMES[entry.id]
                if (!name.isNullOrBlank()) fields.getOrPut(name) { mutableListOf() } += entry.values
            }

            is VorbisComment -> {
                val key = entry.key.uppercase()
                fields.getOrPut(VORBIS_ALIASES[key] ?: key) { mutableListOf() } += entry.value
            }
        }
    }

    fun text(name: String): String =
        fields[name]
            .orEmpty()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()

    return LibraryTags(
        title = text("TITLE"),
        artists = credits(fields["ARTIST"].orEmpty()),
        album = text("ALBUM"),
        albumArtists = credits(fields["ALBUMARTIST"].orEmpty()),
        genre = text("GENRE"),
        label = text("LABEL").ifEmpty { text("PUBLISHER") }.ifEmpty { text("ORGANIZATION") },
        catalogNumber = text("CATALOGNUMBER"),
        year = YEAR_PATTERN.find(text("DATE").ifEmpty { text("YEAR") })?.value?.toInt(),
        releaseId = text("BEATPORT_RELEASE_ID"),
        trackNumber = leadingNumber(text("TRACKNUMBER")),
        discNumber = leadingNumber(text("DISCNUMBER")),
        bpm =
            text("BPM")
                .replace(',', '.')
                .toDoubleOrNull()
                ?.takeIf { it > 0 }
                ?.roundToInt(),
        musicalKey = text("INITIALKEY"),
        energy = text("ENERGYLEVEL").toIntOrNull(),
        taggedAtMs = taggedAt(text("1T_TAGGEDDATE"), zone),
        lengthMs = text("LENGTH").toLongOrNull()?.takeIf { it > 0 },
    )
}

/** Every artist a credit names, in order and once each. */
internal fun credits(values: List<String>): List<String> =
    values
        .flatMap { it.split('\u0000', ',') }
        .flatMap { it.split(MULTI_VALUE_SEPARATOR) }
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinctBy(String::lowercase)

private fun leadingNumber(value: String): Int? =
    value
        .substringBefore('/')
        .trim()
        .toIntOrNull()
        ?.takeIf { it > 0 }

private fun taggedAt(
    value: String,
    zone: ZoneId,
): Long? =
    try {
        value
            .takeIf { it.length >= TAGGED_DATE_LENGTH }
            ?.let { LocalDateTime.parse(it.take(TAGGED_DATE_LENGTH), TAGGED_DATE_FORMAT) }
            ?.atZone(zone)
            ?.toInstant()
            ?.toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }

private const val TAGGED_DATE_LENGTH = 19
private const val MULTI_VALUE_SEPARATOR = "\\\\"
private val TAGGED_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
private val YEAR_PATTERN = Regex("""\b\d{4}\b""")

private val ID3_NAMES =
    mapOf(
        "TIT2" to "TITLE",
        "TT2" to "TITLE",
        "TPE1" to "ARTIST",
        "TP1" to "ARTIST",
        "TALB" to "ALBUM",
        "TAL" to "ALBUM",
        "TPE2" to "ALBUMARTIST",
        "TP2" to "ALBUMARTIST",
        "TCON" to "GENRE",
        "TCO" to "GENRE",
        "TPUB" to "PUBLISHER",
        "TPB" to "PUBLISHER",
        "TDRC" to "DATE",
        "TDRL" to "DATE",
        "TYER" to "DATE",
        "TYE" to "DATE",
        "TRCK" to "TRACKNUMBER",
        "TRK" to "TRACKNUMBER",
        "TPOS" to "DISCNUMBER",
        "TPA" to "DISCNUMBER",
        "TBPM" to "BPM",
        "TBP" to "BPM",
        "TKEY" to "INITIALKEY",
        "TKE" to "INITIALKEY",
        "TLEN" to "LENGTH",
        "TLE" to "LENGTH",
    )

private val VORBIS_ALIASES =
    mapOf(
        "ALBUM ARTIST" to "ALBUMARTIST",
        "ALBUM_ARTIST" to "ALBUMARTIST",
        "TRACK" to "TRACKNUMBER",
        "DISC" to "DISCNUMBER",
        "CATALOG NUMBER" to "CATALOGNUMBER",
        "CATALOG" to "CATALOGNUMBER",
    )
