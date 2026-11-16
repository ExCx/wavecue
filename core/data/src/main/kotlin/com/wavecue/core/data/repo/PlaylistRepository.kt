package com.wavecue.core.data.repo

import androidx.room.withTransaction
import com.wavecue.core.data.db.PlaylistDao
import com.wavecue.core.data.db.PlaylistEntity
import com.wavecue.core.data.db.PlaylistEntryEntity
import com.wavecue.core.data.db.PlaylistTrackRow
import com.wavecue.core.data.db.WaveCueDatabase
import com.wavecue.core.model.NaturalOrder
import com.wavecue.core.model.PlaylistDetail
import com.wavecue.core.model.PlaylistSortMode
import com.wavecue.core.model.PlaylistSummary
import com.wavecue.core.model.PlaylistTrack
import com.wavecue.core.model.QueueItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

@Singleton
class PlaylistRepository @Inject constructor(
    private val database: WaveCueDatabase,
    private val playlistDao: PlaylistDao,
) {
    fun observeSummaries(): Flow<List<PlaylistSummary>> =
        playlistDao.observeSummaries().map { rows ->
            rows.map { PlaylistSummary(it.id, it.name, it.trackCount, it.sortMode) }
        }

    fun observeDetail(playlistId: Long): Flow<PlaylistDetail?> =
        combine(
            playlistDao.observeById(playlistId),
            playlistDao.observeEntries(playlistId),
        ) { playlist, rows ->
            if (playlist == null) return@combine null
            PlaylistDetail(
                id = playlist.id,
                name = playlist.name,
                sortMode = playlist.sortMode,
                tracks = ordered(rows, playlist.sortMode).map {
                    PlaylistTrack(it.entryId, it.position, it.trackId, it.name, it.uri, it.durationMs)
                },
            )
        }

    suspend fun create(name: String): Long = withContext(Dispatchers.IO) {
        playlistDao.insert(PlaylistEntity(name = name.trim()))
    }

    suspend fun rename(playlistId: Long, name: String) = withContext(Dispatchers.IO) {
        val playlist = playlistDao.findById(playlistId) ?: return@withContext
        playlistDao.update(playlist.copy(name = name.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun delete(playlistId: Long) = withContext(Dispatchers.IO) {
        playlistDao.delete(playlistId)
    }

    suspend fun addTrack(playlistId: Long, trackId: Long) = withContext(Dispatchers.IO) {
        val position = playlistDao.maxPosition(playlistId) + 1
        playlistDao.insertEntry(
            PlaylistEntryEntity(playlistId = playlistId, trackId = trackId, position = position)
        )
    }

    suspend fun removeEntry(entryId: Long) = withContext(Dispatchers.IO) {
        playlistDao.deleteEntry(entryId)
    }

    suspend fun moveEntry(playlistId: Long, entryId: Long, direction: Int) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val playlist = playlistDao.findById(playlistId) ?: return@withTransaction
            val rows = ordered(playlistDao.entriesWithTracks(playlistId), playlist.sortMode).toMutableList()
            val index = rows.indexOfFirst { it.entryId == entryId }
            if (index < 0) return@withTransaction
            val target = index + direction
            if (target !in rows.indices) return@withTransaction
            val moved = rows.removeAt(index)
            rows.add(target, moved)
            rows.forEachIndexed { position, row ->
                playlistDao.updateEntryPosition(row.entryId, position)
            }
            playlistDao.update(
                playlist.copy(
                    sortMode = PlaylistSortMode.MANUAL,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun shuffle(playlistId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val playlist = playlistDao.findById(playlistId) ?: return@withTransaction
            val rows = ordered(playlistDao.entriesWithTracks(playlistId), playlist.sortMode)
            val seed = System.nanoTime()
            val shuffled = rows.shuffled(Random(seed))
            shuffled.forEachIndexed { position, row ->
                playlistDao.updateEntryPosition(row.entryId, position)
            }
            playlistDao.update(
                playlist.copy(
                    sortMode = PlaylistSortMode.SHUFFLED,
                    shuffleSeed = seed,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun restoreAlphabetical(playlistId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val playlist = playlistDao.findById(playlistId) ?: return@withTransaction
            val rows = playlistDao.entriesWithTracks(playlistId)
                .sortedWith(compareBy(NaturalOrder) { it.name })
            rows.forEachIndexed { position, row ->
                playlistDao.updateEntryPosition(row.entryId, position)
            }
            playlistDao.update(
                playlist.copy(
                    sortMode = PlaylistSortMode.ALPHABETICAL,
                    updatedAt = System.currentTimeMillis(),
                )
            )
        }
    }

    suspend fun playbackItems(playlistId: Long): List<QueueItem> = withContext(Dispatchers.IO) {
        val playlist = playlistDao.findById(playlistId) ?: return@withContext emptyList()
        val rows = ordered(playlistDao.entriesWithTracks(playlistId), playlist.sortMode)
        rows.map { QueueItem(trackId = it.trackId, uri = it.uri, name = it.name) }
    }

    suspend fun playlist(playlistId: Long): PlaylistEntity? =
        withContext(Dispatchers.IO) { playlistDao.findById(playlistId) }

    /** Full snapshot used by export and diagnostics. */
    suspend fun allDetails(): List<PlaylistDetail> = withContext(Dispatchers.IO) {
        playlistDao.all().map { playlist ->
            val rows = ordered(playlistDao.entriesWithTracks(playlist.id), playlist.sortMode)
            PlaylistDetail(
                id = playlist.id,
                name = playlist.name,
                sortMode = playlist.sortMode,
                tracks = rows.map {
                    PlaylistTrack(it.entryId, it.position, it.trackId, it.name, it.uri, it.durationMs)
                },
            )
        }
    }

    private fun ordered(
        rows: List<PlaylistTrackRow>,
        mode: PlaylistSortMode,
    ): List<PlaylistTrackRow> = when (mode) {
        PlaylistSortMode.ALPHABETICAL ->
            rows.sortedWith(compareBy(NaturalOrder) { it.name })
        else -> rows.sortedBy { it.position }
    }
}
