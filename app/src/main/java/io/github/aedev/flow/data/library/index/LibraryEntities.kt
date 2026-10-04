package io.github.aedev.flow.data.library.index

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One audio file in a music folder, keyed by its folder and location so editing the folder (which
 * changes every playable URI) keeps the row. [path] is folder-relative, for matching playlists.
 */
@Entity(
    tableName = "tracks",
    indices = [
        Index("folderId"),
        Index("releaseKey"),
        Index("genre"),
        Index("label"),
        Index("year"),
        Index("addedAtMs"),
    ],
)
internal data class LibraryTrackEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val location: String,
    val path: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val title: String,
    val artist: String,
    val album: String,
    val releaseKey: String,
    val releaseArtist: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val genre: String,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val label: String,
    val catalogNumber: String,
    val year: Int?,
    val trackNumber: Int?,
    val discNumber: Int?,
    val bpm: Int?,
    val musicalKey: String,
    val energy: Int?,
    val durationMs: Long,
    val addedAtMs: Long,
)

@Entity(
    tableName = "track_artists",
    primaryKeys = ["trackId", "position"],
    foreignKeys = [
        ForeignKey(entity = LibraryTrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("name")],
)
internal data class LibraryTrackArtistEntity(
    val trackId: String,
    val position: Int,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
)

@Entity(tableName = "playlists", indices = [Index("folderId")])
internal data class LibraryPlaylistEntity(
    @PrimaryKey val id: String,
    val folderId: String,
    val location: String,
    val path: String,
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

@Entity(
    tableName = "playlist_entries",
    primaryKeys = ["playlistId", "position"],
    foreignKeys = [
        ForeignKey(
            entity = LibraryPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = LibraryTrackEntity::class, parentColumns = ["id"], childColumns = ["trackId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("trackId")],
)
internal data class LibraryPlaylistEntryEntity(
    val playlistId: String,
    val position: Int,
    val trackId: String,
)

/** The cover of a release, saved once from the first of its files that carries one. */
@Entity(tableName = "release_artwork")
internal data class LibraryArtworkEntity(
    @PrimaryKey val releaseKey: String,
    val path: String,
)

@Entity(tableName = "meta")
internal data class LibraryMetaEntity(
    @PrimaryKey val key: String,
    val value: String,
)

internal data class LibraryTrackStamp(
    val id: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
)

internal data class LibraryTrackRow(
    @Embedded val track: LibraryTrackEntity,
    val artworkPath: String?,
)

/** A release as the index groups it: by its Beatport release id, or its album, album artist and label. */
internal data class LibraryReleaseRow(
    val releaseKey: String,
    val album: String,
    val firstTitle: String,
    val releaseArtist: String,
    val artist: String,
    val label: String,
    val catalogNumber: String,
    val year: Int?,
    val trackCount: Int,
    val addedAtMs: Long,
    val artworkPath: String?,
)

/** An artist, label, genre or year with how many tracks it has and the cover of its newest release. */
internal data class LibraryGroupRow(
    val name: String,
    val trackCount: Int,
    val releaseCount: Int,
    val artworkPath: String?,
    val firstYear: Int?,
    val lastYear: Int?,
)

internal data class LibraryPlaylistRow(
    val id: String,
    val name: String,
    val trackCount: Int,
    val artworkPath: String?,
)
