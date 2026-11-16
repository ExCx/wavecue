package com.wavecue.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wavecue.core.model.PlaylistSortMode
import kotlinx.coroutines.flow.Flow

@Dao
interface RootDao {
    @Query("SELECT * FROM library_roots ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<LibraryRootEntity>>

    @Query("SELECT * FROM library_roots ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<LibraryRootEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(root: LibraryRootEntity): Long

    @Query("DELETE FROM library_roots WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE uri = :uri LIMIT 1")
    suspend fun findByUri(uri: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TrackEntity?

    @Query("SELECT * FROM tracks ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<TrackEntity>

    @Query("SELECT * FROM tracks")
    fun observeAll(): Flow<List<TrackEntity>>

    @Insert
    suspend fun insert(track: TrackEntity): Long

    @Update
    suspend fun update(track: TrackEntity)

    @Query(
        "UPDATE tracks SET durationMs = :durationMs, sampleRate = :sampleRate, channelCount = :channels, " +
            "bitDepth = :bitDepth, codec = :codec, waveformPath = :waveformPath, analysisState = :state, " +
            "updatedAt = :now WHERE id = :id"
    )
    suspend fun updateAnalysis(
        id: Long,
        durationMs: Long,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int,
        codec: String,
        waveformPath: String?,
        state: Int,
        now: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE tracks SET analysisState = :state, updatedAt = :now WHERE id = :id")
    suspend fun updateAnalysisState(id: Long, state: Int, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM tracks WHERE id NOT IN (SELECT DISTINCT trackId FROM playlist_entries)")
    suspend fun deleteOrphans()

    @Query("UPDATE tracks SET waveformPath = NULL, analysisState = 0")
    suspend fun resetAllAnalysis()
}

@Dao
interface FlagDao {
    @Query("SELECT * FROM flags WHERE trackId = :trackId ORDER BY timeMs ASC, id ASC")
    fun observeForTrack(trackId: Long): Flow<List<FlagEntity>>

    @Query("SELECT * FROM flags WHERE trackId = :trackId ORDER BY timeMs ASC, id ASC")
    suspend fun forTrack(trackId: Long): List<FlagEntity>

    @Query("SELECT * FROM flags WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): FlagEntity?

    @Query("SELECT * FROM flags ORDER BY trackId, timeMs")
    suspend fun all(): List<FlagEntity>

    @Insert
    suspend fun insert(flag: FlagEntity): Long

    @Update
    suspend fun update(flag: FlagEntity)

    @Query("DELETE FROM flags WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE trackId = :trackId ORDER BY timeMs ASC, id ASC")
    fun observeForTrack(trackId: Long): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE trackId = :trackId ORDER BY timeMs ASC, id ASC")
    suspend fun forTrack(trackId: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes ORDER BY trackId, timeMs")
    suspend fun all(): List<NoteEntity>

    @Insert
    suspend fun insert(note: NoteEntity): Long

    @Update
    suspend fun update(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun delete(id: Long)
}

data class PlaylistSummaryRow(
    val id: Long,
    val name: String,
    val sortMode: PlaylistSortMode,
    val trackCount: Int,
)

data class PlaylistTrackRow(
    val entryId: Long,
    val position: Int,
    val trackId: Long,
    val name: String,
    val uri: String,
    val durationMs: Long,
)

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.id, p.name, p.sortMode, " +
            "(SELECT COUNT(*) FROM playlist_entries e WHERE e.playlistId = p.id) AS trackCount " +
            "FROM playlists p ORDER BY p.name COLLATE NOCASE"
    )
    fun observeSummaries(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT * FROM playlists ORDER BY name COLLATE NOCASE")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun findById(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observeById(id: Long): Flow<PlaylistEntity?>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Update
    suspend fun update(playlist: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "SELECT e.id AS entryId, e.position, t.id AS trackId, t.name, t.uri, t.durationMs " +
            "FROM playlist_entries e JOIN tracks t ON t.id = e.trackId " +
            "WHERE e.playlistId = :playlistId ORDER BY e.position ASC"
    )
    fun observeEntries(playlistId: Long): Flow<List<PlaylistTrackRow>>

    @Query(
        "SELECT e.id AS entryId, e.position, t.id AS trackId, t.name, t.uri, t.durationMs " +
            "FROM playlist_entries e JOIN tracks t ON t.id = e.trackId " +
            "WHERE e.playlistId = :playlistId ORDER BY e.position ASC"
    )
    suspend fun entriesWithTracks(playlistId: Long): List<PlaylistTrackRow>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert
    suspend fun insertEntry(entry: PlaylistEntryEntity): Long

    @Query("UPDATE playlist_entries SET position = :position WHERE id = :entryId")
    suspend fun updateEntryPosition(entryId: Long, position: Int)

    @Query("DELETE FROM playlist_entries WHERE id = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deleteEntries(playlistId: Long)

    @Query("SELECT * FROM playlist_entries ORDER BY playlistId, position")
    suspend fun allEntries(): List<PlaylistEntryEntity>

    @Query("SELECT * FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position")
    suspend fun entriesFor(playlistId: Long): List<PlaylistEntryEntity>
}
