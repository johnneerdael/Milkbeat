package io.github.aedev.flow.data.library.index

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery

/** Which tracks a query covers. Every set field narrows it; an empty filter is the whole library. */
internal data class LibraryFilter(
    val genre: String? = null,
    val artist: String? = null,
    val label: String? = null,
    val year: Int? = null,
    val releaseKey: String? = null,
    val playlistId: String? = null,
)

internal enum class TrackOrder { RELEASE, NEWEST, PLAYLIST }

internal enum class ReleaseOrder { RECENT, NEWEST }

internal enum class GroupKind { ARTIST, LABEL, YEAR, GENRE }

internal enum class GroupOrder { TRACKS, NAME, NAME_DESCENDING }

/** Builds the index's browse queries, with every value bound rather than spliced into the SQL. */
internal object LibraryQueries {
    fun tracks(
        filter: LibraryFilter,
        order: TrackOrder,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
    ): SupportSQLiteQuery {
        val scope = Scope(filter)
        val orderBy =
            when (order) {
                TrackOrder.RELEASE -> "t.discNumber, t.trackNumber, t.title COLLATE NOCASE"
                TrackOrder.NEWEST -> "t.year IS NULL, t.year DESC, t.addedAtMs DESC, t.releaseKey, t.discNumber, t.trackNumber"
                TrackOrder.PLAYLIST -> "pe.position"
            }
        return scope.query(
            "SELECT t.*, a.path AS artworkPath FROM tracks t ${scope.joins} " +
                "LEFT JOIN release_artwork a ON a.releaseKey = t.releaseKey ${scope.where} ORDER BY $orderBy LIMIT ? OFFSET ?",
            limit,
            offset,
        )
    }

    fun releases(
        filter: LibraryFilter,
        order: ReleaseOrder,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
    ): SupportSQLiteQuery {
        val scope = Scope(filter)
        val orderBy =
            when (order) {
                ReleaseOrder.RECENT -> "MAX(t.addedAtMs) DESC, MAX(t.year) DESC"
                ReleaseOrder.NEWEST -> "MAX(t.year) IS NULL, MAX(t.year) DESC, MAX(t.addedAtMs) DESC"
            }
        return scope.query(
            "SELECT t.releaseKey AS releaseKey, MAX(t.album) AS album, MIN(t.title) AS firstTitle, " +
                "MAX(t.releaseArtist) AS releaseArtist, MIN(t.artist) AS artist, MAX(t.label) AS label, " +
                "MAX(t.catalogNumber) AS catalogNumber, MAX(t.year) AS year, COUNT(*) AS trackCount, " +
                "MAX(t.addedAtMs) AS addedAtMs, MAX(a.path) AS artworkPath FROM tracks t ${scope.joins} " +
                "LEFT JOIN release_artwork a ON a.releaseKey = t.releaseKey ${scope.where} " +
                "GROUP BY t.releaseKey ORDER BY $orderBy LIMIT ? OFFSET ?",
            limit,
            offset,
        )
    }

    /** Artists, labels, years or genres among [filter]'s tracks; [named] keeps only the one with that name. */
    fun groups(
        kind: GroupKind,
        filter: LibraryFilter,
        order: GroupOrder,
        limit: Int = Int.MAX_VALUE,
        offset: Int = 0,
        named: String? = null,
    ): SupportSQLiteQuery {
        val scope = Scope(filter, extraConditions = listOfNotNull(kind.presentCondition), match = named?.let { kind.column to it })
        val orderBy =
            when (order) {
                GroupOrder.TRACKS -> "trackCount DESC, name COLLATE NOCASE"
                GroupOrder.NAME -> "name COLLATE NOCASE"
                GroupOrder.NAME_DESCENDING -> "name DESC"
            }
        val source = if (kind == GroupKind.ARTIST) "track_artists g JOIN tracks t ON t.id = g.trackId" else "tracks t"
        return scope.query(
            "SELECT ${kind.column} AS name, COUNT(DISTINCT t.id) AS trackCount, COUNT(DISTINCT t.releaseKey) AS releaseCount, " +
                "MIN(t.year) AS firstYear, MAX(t.year) AS lastYear, ${kind.artwork} AS artworkPath " +
                "FROM $source ${scope.joins} ${scope.where} GROUP BY ${kind.column} ORDER BY $orderBy LIMIT ? OFFSET ?",
            limit,
            offset,
        )
    }

    private val GroupKind.column: String
        get() =
            when (this) {
                GroupKind.ARTIST -> "g.name"
                GroupKind.LABEL -> "t.label"
                GroupKind.YEAR -> "t.year"
                GroupKind.GENRE -> "t.genre"
            }

    private val GroupKind.presentCondition: String?
        get() =
            when (this) {
                GroupKind.ARTIST -> null
                GroupKind.LABEL -> "t.label != ''"
                GroupKind.YEAR -> "t.year IS NOT NULL"
                GroupKind.GENRE -> "t.genre != ''"
            }

    /** The cover of the group's newest release that has one. */
    private val GroupKind.artwork: String
        get() {
            val newest = "ORDER BY t2.year IS NULL, t2.year DESC, t2.addedAtMs DESC LIMIT 1)"
            return when (this) {
                GroupKind.ARTIST -> {
                    "(SELECT a2.path FROM track_artists g2 JOIN tracks t2 ON t2.id = g2.trackId " +
                        "JOIN release_artwork a2 ON a2.releaseKey = t2.releaseKey WHERE g2.name = g.name $newest"
                }

                GroupKind.LABEL -> {
                    "(SELECT a2.path FROM tracks t2 JOIN release_artwork a2 ON a2.releaseKey = t2.releaseKey " +
                        "WHERE t2.label = t.label $newest"
                }

                GroupKind.YEAR -> {
                    "(SELECT a2.path FROM tracks t2 JOIN release_artwork a2 ON a2.releaseKey = t2.releaseKey " +
                        "WHERE t2.year = t.year $newest"
                }

                GroupKind.GENRE -> {
                    "NULL"
                }
            }
        }

    private class Scope(
        filter: LibraryFilter,
        extraConditions: List<String> = emptyList(),
        match: Pair<String, String>? = null,
    ) {
        private val args = mutableListOf<Any>()
        val joins: String
        val where: String

        init {
            val joinClauses = mutableListOf<String>()
            filter.artist?.let {
                joinClauses += "JOIN track_artists fa ON fa.trackId = t.id AND fa.name = ?"
                args += it
            }
            filter.playlistId?.let {
                joinClauses += "JOIN playlist_entries pe ON pe.trackId = t.id AND pe.playlistId = ?"
                args += it
            }
            joins = joinClauses.joinToString(" ")
            val conditions = extraConditions.toMutableList()
            match?.let { (column, value) ->
                conditions += "$column = ?"
                args += value
            }
            filter.genre?.let {
                conditions += "t.genre = ?"
                args += it
            }
            filter.label?.let {
                conditions += "t.label = ?"
                args += it
            }
            filter.year?.let {
                conditions += "t.year = ?"
                args += it
            }
            filter.releaseKey?.let {
                conditions += "t.releaseKey = ?"
                args += it
            }
            where = if (conditions.isEmpty()) "" else "WHERE " + conditions.joinToString(" AND ")
        }

        fun query(
            sql: String,
            limit: Int,
            offset: Int,
        ): SupportSQLiteQuery = SimpleSQLiteQuery(sql, (args + limit + offset).toTypedArray())
    }
}
