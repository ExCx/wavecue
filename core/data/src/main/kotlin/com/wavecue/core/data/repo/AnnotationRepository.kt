package com.wavecue.core.data.repo

import com.wavecue.core.data.db.FlagDao
import com.wavecue.core.data.db.FlagEntity
import com.wavecue.core.data.db.NoteDao
import com.wavecue.core.data.db.NoteEntity
import com.wavecue.core.model.FlagItem
import com.wavecue.core.model.NoteItem
import com.wavecue.core.model.SilenceRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnnotationRepository @Inject constructor(
    private val flagDao: FlagDao,
    private val noteDao: NoteDao,
) {
    fun flags(trackId: Long): Flow<List<FlagItem>> =
        flagDao.observeForTrack(trackId).map { flags ->
            flags.map { FlagItem(it.id, it.trackId, it.timeMs, it.title, it.automatic) }
        }

    fun notes(trackId: Long): Flow<List<NoteItem>> =
        noteDao.observeForTrack(trackId).map { notes ->
            notes.map { NoteItem(it.id, it.trackId, it.timeMs, it.body, it.createdAt, it.updatedAt) }
        }

    suspend fun addFlag(
        trackId: Long,
        timeMs: Long,
        title: String,
        automatic: Boolean = false,
    ): Long = withContext(Dispatchers.IO) {
        flagDao.insert(
            FlagEntity(
                trackId = trackId,
                timeMs = timeMs.coerceAtLeast(0),
                title = title.trim().ifEmpty { "Flag" },
                automatic = automatic,
            )
        )
    }

    suspend fun updateFlag(id: Long, timeMs: Long, title: String) = withContext(Dispatchers.IO) {
        val existing = flagDao.findById(id) ?: return@withContext
        flagDao.update(
            existing.copy(
                timeMs = timeMs.coerceAtLeast(0),
                title = title.trim().ifEmpty { "Flag" },
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteFlag(id: Long) = withContext(Dispatchers.IO) {
        flagDao.delete(id)
    }

    suspend fun addNote(trackId: Long, timeMs: Long, body: String): Long = withContext(Dispatchers.IO) {
        noteDao.insert(
            NoteEntity(
                trackId = trackId,
                timeMs = timeMs.coerceAtLeast(0),
                body = body.trim(),
            )
        )
    }

    suspend fun updateNote(id: Long, timeMs: Long, body: String) = withContext(Dispatchers.IO) {
        val existing = noteDao.findById(id) ?: return@withContext
        noteDao.update(
            existing.copy(
                timeMs = timeMs.coerceAtLeast(0),
                body = body.trim(),
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun deleteNote(id: Long) = withContext(Dispatchers.IO) {
        noteDao.delete(id)
    }

    /**
     * Converts accepted silence regions into automatic gap flags placed at the
     * midpoint of each region. Returns the number of flags created.
     */
    suspend fun addSilenceFlags(
        trackId: Long,
        regions: List<SilenceRegion>,
        startIndex: Int = 1,
    ): Int = withContext(Dispatchers.IO) {
        var index = startIndex
        for (region in regions) {
            val midpoint = (region.startMs + region.endMs) / 2
            flagDao.insert(
                FlagEntity(
                    trackId = trackId,
                    timeMs = midpoint,
                    title = "Gap $index",
                    automatic = true,
                )
            )
            index++
        }
        regions.size
    }

    suspend fun allFlags(): List<FlagEntity> = withContext(Dispatchers.IO) { flagDao.all() }

    suspend fun allNotes(): List<NoteEntity> = withContext(Dispatchers.IO) { noteDao.all() }

    suspend fun flagsOnce(trackId: Long): List<FlagEntity> =
        withContext(Dispatchers.IO) { flagDao.forTrack(trackId) }

    suspend fun notesOnce(trackId: Long): List<NoteEntity> =
        withContext(Dispatchers.IO) { noteDao.forTrack(trackId) }
}
