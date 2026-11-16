package com.wavecue.app.export

import android.content.Context
import android.net.Uri
import com.wavecue.core.data.db.FlagEntity
import com.wavecue.core.data.db.NoteEntity
import com.wavecue.core.data.db.TrackEntity
import com.wavecue.core.data.repo.AnnotationRepository
import com.wavecue.core.data.repo.LibraryRepository
import com.wavecue.core.data.repo.PlaylistRepository
import com.wavecue.core.model.TimeFormat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class TrackExport(
    val id: Long,
    val name: String,
    val uri: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
    val codec: String,
)

@Serializable
data class PlaylistTrackExport(
    val trackId: Long,
    val name: String,
    val position: Int,
)

@Serializable
data class PlaylistExport(
    val id: Long,
    val name: String,
    val sortMode: String,
    val tracks: List<PlaylistTrackExport>,
)

@Serializable
data class FlagExport(
    val trackId: Long,
    val timeMs: Long,
    val timecode: String,
    val title: String,
    val automatic: Boolean,
)

@Serializable
data class NoteExport(
    val trackId: Long,
    val timeMs: Long,
    val timecode: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Serializable
data class ExportBundle(
    val app: String = "WaveCue",
    val schema: Int = 1,
    val exportedAt: Long,
    val tracks: List<TrackExport>,
    val playlists: List<PlaylistExport>,
    val flags: List<FlagExport>,
    val notes: List<NoteExport>,
)

/**
 * Export is explicit and user-driven: JSON for a full library backup, Markdown for
 * reading notes outside the app. No sidecar files are ever written next to audio.
 */
@Singleton
class ExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val annotationRepository: AnnotationRepository,
    private val playlistRepository: PlaylistRepository,
) {
    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    suspend fun buildBundle(): ExportBundle = withContext(Dispatchers.IO) {
        val tracks = library.allTracks()
        val playlists = playlistRepository.allDetails()
        val flags = annotationRepository.allFlags()
        val notes = annotationRepository.allNotes()
        ExportBundle(
            exportedAt = System.currentTimeMillis(),
            tracks = tracks.map { it.toExport() },
            playlists = playlists.map { detail ->
                PlaylistExport(
                    id = detail.id,
                    name = detail.name,
                    sortMode = detail.sortMode.name,
                    tracks = detail.tracks.map {
                        PlaylistTrackExport(trackId = it.trackId, name = it.name, position = it.position)
                    },
                )
            },
            flags = flags.map {
                FlagExport(it.trackId, it.timeMs, TimeFormat.timecode(it.timeMs), it.title, it.automatic)
            },
            notes = notes.map {
                NoteExport(it.trackId, it.timeMs, TimeFormat.timecode(it.timeMs), it.body, it.createdAt, it.updatedAt)
            },
        )
    }

    suspend fun exportJsonTo(uri: Uri): Int = withContext(Dispatchers.IO) {
        val bundle = buildBundle()
        val text = json.encodeToString(bundle)
        write(uri, text)
        bundle.notes.size + bundle.flags.size
    }

    suspend fun exportTrackMarkdown(uri: Uri, trackId: Long): Boolean = withContext(Dispatchers.IO) {
        val track = library.track(trackId) ?: return@withContext false
        val flags = annotationRepository.flagsOnce(trackId)
        val notes = annotationRepository.notesOnce(trackId)
        write(uri, markdown(track, flags, notes))
        true
    }

    suspend fun buildTrackMarkdown(trackId: Long): String = withContext(Dispatchers.IO) {
        val track = library.track(trackId) ?: return@withContext ""
        val flags = annotationRepository.flagsOnce(trackId)
        val notes = annotationRepository.notesOnce(trackId)
        markdown(track, flags, notes)
    }

    private fun markdown(track: TrackEntity, flags: List<FlagEntity>, notes: List<NoteEntity>): String = buildString {
        appendLine("# ${track.name}")
        appendLine()
        appendLine("- Duration: ${TimeFormat.short(track.durationMs)}")
        if (track.sampleRate > 0) {
            appendLine(
                "- Format: ${track.codec.ifBlank { "audio" }} · ${track.sampleRate} Hz · " +
                    "${track.bitDepth}-bit · ${track.channelCount} ch"
            )
        }
        appendLine()
        appendLine("## Flags")
        if (flags.isEmpty()) {
            appendLine("_None_")
        } else {
            flags.forEach { appendLine("- `${TimeFormat.timecode(it.timeMs)}` — ${it.title}") }
        }
        appendLine()
        appendLine("## Notes")
        if (notes.isEmpty()) {
            appendLine("_None_")
        } else {
            notes.forEach {
                appendLine("- `${TimeFormat.timecode(it.timeMs)}` — ${it.body.replace("\n", " ")}")
            }
        }
    }

    private fun write(uri: Uri, text: String) {
        val stream = context.contentResolver.openOutputStream(uri)
            ?: throw IOException("Cannot open the selected destination")
        stream.bufferedWriter().use { it.write(text) }
    }

    private fun TrackEntity.toExport() = TrackExport(
        id = id,
        name = name,
        uri = uri,
        durationMs = durationMs,
        sampleRate = sampleRate,
        channelCount = channelCount,
        bitDepth = bitDepth,
        codec = codec,
    )
}
