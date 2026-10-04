package io.github.aedev.flow.data.library.index

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

internal data class LibraryPathId(
    val path: String,
    val id: String,
)

@Dao
internal interface LibraryDao {
    @Query("SELECT id, sizeBytes, modifiedMs FROM tracks WHERE folderId = :folderId")
    suspend fun trackStamps(folderId: String): List<LibraryTrackStamp>

    @Query("SELECT path, id FROM tracks WHERE folderId = :folderId")
    suspend fun trackPaths(folderId: String): List<LibraryPathId>

    @Query("SELECT id, sizeBytes, modifiedMs FROM playlists WHERE folderId = :folderId")
    suspend fun playlistStamps(folderId: String): List<LibraryTrackStamp>

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int

    @Query("SELECT COUNT(*) FROM tracks")
    fun observeTrackCount(): Flow<Int>

    @Upsert
    suspend fun upsertTracks(tracks: List<LibraryTrackEntity>)

    @Query("DELETE FROM track_artists WHERE trackId IN (:trackIds)")
    suspend fun deleteArtists(trackIds: List<String>)

    @Insert
    suspend fun insertArtists(artists: List<LibraryTrackArtistEntity>)

    /** Writes a batch of read files with their credits, replacing what an earlier read stored. */
    @Transaction
    suspend fun saveTracks(
        tracks: List<LibraryTrackEntity>,
        artists: List<LibraryTrackArtistEntity>,
    ) {
        upsertTracks(tracks)
        deleteArtists(tracks.map { it.id })
        insertArtists(artists)
    }

    @Query("DELETE FROM tracks WHERE id IN (:ids)")
    suspend fun deleteTracks(ids: List<String>)

    @Query("DELETE FROM tracks WHERE folderId NOT IN (:folderIds)")
    suspend fun deleteTracksOutside(folderIds: List<String>)

    @Query("DELETE FROM playlists WHERE folderId NOT IN (:folderIds)")
    suspend fun deletePlaylistsOutside(folderIds: List<String>)

    @Query("DELETE FROM playlists WHERE id IN (:ids)")
    suspend fun deletePlaylists(ids: List<String>)

    @Upsert
    suspend fun upsertPlaylist(playlist: LibraryPlaylistEntity)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deletePlaylistEntries(playlistId: String)

    @Insert
    suspend fun insertPlaylistEntries(entries: List<LibraryPlaylistEntryEntity>)

    @Transaction
    suspend fun savePlaylist(
        playlist: LibraryPlaylistEntity,
        trackIds: List<String>,
    ) {
        upsertPlaylist(playlist)
        deletePlaylistEntries(playlist.id)
        insertPlaylistEntries(trackIds.mapIndexed { position, trackId -> LibraryPlaylistEntryEntity(playlist.id, position, trackId) })
    }

    @Query("SELECT * FROM playlists WHERE folderId = :folderId")
    suspend fun playlists(folderId: String): List<LibraryPlaylistEntity>

    @Query("SELECT releaseKey FROM release_artwork")
    suspend fun artworkKeys(): List<String>

    @Query("SELECT * FROM release_artwork WHERE releaseKey = :releaseKey")
    suspend fun artwork(releaseKey: String): LibraryArtworkEntity?

    @Upsert
    suspend fun upsertArtwork(artwork: LibraryArtworkEntity)

    @Query("SELECT * FROM release_artwork WHERE releaseKey NOT IN (SELECT releaseKey FROM tracks)")
    suspend fun orphanArtwork(): List<LibraryArtworkEntity>

    @Query("SELECT id FROM tracks WHERE releaseKey = :releaseKey")
    suspend fun releaseTrackIds(releaseKey: String): List<String>

    @Query("DELETE FROM release_artwork WHERE releaseKey = :releaseKey")
    suspend fun deleteArtworkOf(releaseKey: String)

    @Query("DELETE FROM release_artwork WHERE releaseKey NOT IN (SELECT releaseKey FROM tracks)")
    suspend fun deleteOrphanArtwork()

    @Query("SELECT value FROM meta WHERE `key` = :key")
    suspend fun meta(key: String): String?

    @Query("SELECT value FROM meta WHERE `key` = :key")
    fun observeMeta(key: String): Flow<String?>

    @Upsert
    suspend fun setMeta(meta: LibraryMetaEntity)

    @Query(
        """
        SELECT p.id AS id, p.name AS name, COUNT(e.trackId) AS trackCount,
            (SELECT a.path FROM playlist_entries e2
                JOIN tracks t2 ON t2.id = e2.trackId
                JOIN release_artwork a ON a.releaseKey = t2.releaseKey
                WHERE e2.playlistId = p.id ORDER BY e2.position LIMIT 1) AS artworkPath
        FROM playlists p LEFT JOIN playlist_entries e ON e.playlistId = p.id
        GROUP BY p.id HAVING COUNT(e.trackId) > 0 ORDER BY p.name COLLATE NOCASE
        """,
    )
    suspend fun playlistRows(): List<LibraryPlaylistRow>

    @Query("SELECT name FROM playlists WHERE id = :id")
    suspend fun playlistName(id: String): String?

    @RawQuery
    suspend fun tracks(query: SupportSQLiteQuery): List<LibraryTrackRow>

    @RawQuery
    suspend fun releases(query: SupportSQLiteQuery): List<LibraryReleaseRow>

    @RawQuery
    suspend fun groups(query: SupportSQLiteQuery): List<LibraryGroupRow>
}
