package com.wavecue.core.data.saf

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class DocumentNode(
    val documentId: String,
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val mimeType: String,
)

@Singleton
class SafRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val resolver get() = context.contentResolver

    suspend fun persistPermission(treeUri: Uri) = withContext(Dispatchers.IO) {
        try {
            resolver.takePersistableUriPermission(treeUri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Providers that do not support persistable grants still work for this session.
        }
    }

    suspend fun displayName(treeUri: Uri): String = withContext(Dispatchers.IO) {
        val documentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        queryName(documentUri) ?: treeUri.lastPathSegment ?: "Folder"
    }

    private fun queryName(documentUri: Uri): String? {
        return try {
            resolver.query(
                documentUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun listChildren(treeUri: Uri, parentDocumentId: String?): List<DocumentNode> =
        withContext(Dispatchers.IO) {
            val parent = parentDocumentId ?: DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            val nodes = ArrayList<DocumentNode>()
            try {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(projection[0])
                    val nameIndex = cursor.getColumnIndexOrThrow(projection[1])
                    val mimeIndex = cursor.getColumnIndexOrThrow(projection[2])
                    val sizeIndex = cursor.getColumnIndexOrThrow(projection[3])
                    val modifiedIndex = cursor.getColumnIndexOrThrow(projection[4])
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIndex) ?: continue
                        val name = cursor.getString(nameIndex) ?: continue
                        val mime = cursor.getString(mimeIndex).orEmpty()
                        val isDirectory =
                            mime == DocumentsContract.Document.MIME_TYPE_DIR
                        if (!isDirectory && !isAudio(name, mime)) continue
                        nodes.add(
                            DocumentNode(
                                documentId = id,
                                uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                                name = name,
                                isDirectory = isDirectory,
                                sizeBytes = if (cursor.isNull(sizeIndex)) 0 else cursor.getLong(sizeIndex),
                                lastModified = if (cursor.isNull(modifiedIndex)) 0 else cursor.getLong(modifiedIndex),
                                mimeType = mime,
                            )
                        )
                    }
                }
            } catch (_: SecurityException) {
                // Permission was revoked; the UI surfaces the empty state.
            } catch (_: Exception) {
                // A single unreadable provider should not crash browsing.
            }
            nodes
        }

    suspend fun documentInfo(treeUri: Uri, documentId: String): DocumentNode? =
        withContext(Dispatchers.IO) {
            val uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
            try {
                resolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val mime = cursor.getString(2).orEmpty()
                    DocumentNode(
                        documentId = documentId,
                        uri = uri,
                        name = cursor.getString(1) ?: return@use null,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        sizeBytes = if (cursor.isNull(3)) 0 else cursor.getLong(3),
                        lastModified = if (cursor.isNull(4)) 0 else cursor.getLong(4),
                        mimeType = mime,
                    )
                }
            } catch (_: Exception) {
                null
            }
        }

    companion object {
        private val audioExtensions = setOf("wav", "wave", "bwf", "mp3")

        fun isAudio(name: String, mimeType: String): Boolean {
            val ext = if ('.' in name) name.substringAfterLast('.').lowercase() else ""
            if (ext in audioExtensions) return true
            if (ext.isNotEmpty()) return false
            return mimeType == "audio/mpeg" || mimeType == "audio/wav" || mimeType == "audio/x-wav" ||
                mimeType == "audio/wave"
        }
    }
}
