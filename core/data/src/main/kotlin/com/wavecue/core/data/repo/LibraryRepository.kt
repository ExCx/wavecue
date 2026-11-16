package com.wavecue.core.data.repo

import android.content.Context
import android.net.Uri
import com.wavecue.core.data.db.LibraryRootEntity
import com.wavecue.core.data.db.RootDao
import com.wavecue.core.data.db.TrackDao
import com.wavecue.core.data.db.TrackEntity
import com.wavecue.core.data.saf.DocumentNode
import com.wavecue.core.data.saf.SafRepository
import com.wavecue.core.model.LoadedMedia
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class LibraryRoot(
    val id: Long,
    val treeUri: String,
    val name: String,
)

@Singleton
class LibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val rootDao: RootDao,
    private val trackDao: TrackDao,
    private val saf: SafRepository,
) {
    fun observeRoots(): Flow<List<LibraryRoot>> =
        rootDao.observeAll().map { roots ->
            roots.map { LibraryRoot(it.id, it.treeUri, it.name) }
        }

    suspend fun addRoot(treeUri: Uri): Long = withContext(Dispatchers.IO) {
        saf.persistPermission(treeUri)
        val name = saf.displayName(treeUri)
        val existing = rootDao.all().firstOrNull { it.treeUri == treeUri.toString() }
        if (existing != null) return@withContext existing.id
        return@withContext rootDao.insert(LibraryRootEntity(treeUri = treeUri.toString(), name = name))
    }

    suspend fun removeRoot(id: Long) = withContext(Dispatchers.IO) {
        rootDao.delete(id)
    }

    suspend fun rootById(id: Long): LibraryRoot? = withContext(Dispatchers.IO) {
        rootDao.all().firstOrNull { it.id == id }?.let { LibraryRoot(it.id, it.treeUri, it.name) }
    }

    suspend fun listChildren(treeUri: Uri, documentId: String?): List<DocumentNode> =
        saf.listChildren(treeUri, documentId)

    suspend fun ensureTrack(node: DocumentNode): TrackEntity = withContext(Dispatchers.IO) {
        ensureTrack(node.uri, node.name, node.sizeBytes, node.lastModified)
    }

    suspend fun ensureTrack(
        uri: Uri,
        name: String,
        sizeBytes: Long = 0,
        lastModified: Long = 0,
    ): TrackEntity = withContext(Dispatchers.IO) {
        val uriString = uri.toString()
        val existing = trackDao.findByUri(uriString)
        if (existing == null) {
            val id = trackDao.insert(
                TrackEntity(
                    uri = uriString,
                    name = name,
                    sizeBytes = sizeBytes,
                    lastModified = lastModified,
                )
            )
            return@withContext trackDao.findById(id)!!
        }

        val identityChanged =
            (sizeBytes > 0 && existing.sizeBytes > 0 && sizeBytes != existing.sizeBytes) ||
                (lastModified > 0 && existing.lastModified > 0 && lastModified != existing.lastModified)

        val newSize = if (sizeBytes > 0) sizeBytes else existing.sizeBytes
        val newModified = if (lastModified > 0) lastModified else existing.lastModified

        if (!identityChanged && existing.name == name && existing.sizeBytes == newSize && existing.lastModified == newModified) {
            return@withContext existing
        }

        val updated = existing.copy(
            name = name,
            sizeBytes = newSize,
            lastModified = newModified,
            updatedAt = System.currentTimeMillis(),
        )
        if (identityChanged) {
            File(context.filesDir, "waveforms/${existing.id}.wfc").delete()
            trackDao.update(
                updated.copy(
                    waveformPath = null,
                    analysisState = 0,
                    durationMs = 0,
                    sampleRate = 0,
                    channelCount = 0,
                    bitDepth = 0,
                    codec = "",
                )
            )
        } else {
            trackDao.update(updated)
        }
        trackDao.findById(existing.id)!!
    }

    suspend fun track(id: Long): TrackEntity? = withContext(Dispatchers.IO) { trackDao.findById(id) }

    suspend fun trackByUri(uri: String): TrackEntity? = withContext(Dispatchers.IO) {
        trackDao.findByUri(uri)
    }

    suspend fun allTracks(): List<TrackEntity> = withContext(Dispatchers.IO) { trackDao.all() }

    suspend fun applyLoadedMedia(trackId: Long, media: LoadedMedia) = withContext(Dispatchers.IO) {
        val track = trackDao.findById(trackId) ?: return@withContext
        trackDao.update(
            track.copy(
                durationMs = if (media.durationMs > 0) media.durationMs else track.durationMs,
                sampleRate = media.sampleRate,
                channelCount = media.channelCount,
                bitDepth = media.bitDepth,
                codec = media.codec,
                updatedAt = System.currentTimeMillis(),
            )
        )
    }
}
