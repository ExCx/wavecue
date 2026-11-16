package com.wavecue.core.model

/** A lightweight reference to a playable track. [id] is the track row id in the local database. */
data class QueueItem(
    val trackId: Long,
    val uri: String,
    val name: String,
)

data class TrackInfo(
    val id: Long,
    val uri: String,
    val name: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
    val codec: String,
)

data class LoadedMedia(
    val uri: String,
    val name: String,
    val durationMs: Long,
    val sampleRate: Int,
    val channelCount: Int,
    val bitDepth: Int,
    val codec: String,
    val isFloat: Boolean,
)

data class FlagItem(
    val id: Long,
    val trackId: Long,
    val timeMs: Long,
    val title: String,
    val automatic: Boolean = false,
)

data class NoteItem(
    val id: Long,
    val trackId: Long,
    val timeMs: Long,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
)

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val trackCount: Int,
    val sortMode: PlaylistSortMode,
)

data class PlaylistTrack(
    val entryId: Long,
    val position: Int,
    val trackId: Long,
    val name: String,
    val uri: String,
    val durationMs: Long,
)

data class PlaylistDetail(
    val id: Long,
    val name: String,
    val sortMode: PlaylistSortMode,
    val tracks: List<PlaylistTrack>,
)

enum class PlaylistSortMode { ALPHABETICAL, MANUAL, SHUFFLED }

data class SilenceRegion(
    val startMs: Long,
    val endMs: Long,
) {
    val durationMs: Long get() = endMs - startMs
}

data class PlaybackDiagnostics(
    val sinkName: String = "idle",
    val sourceCodec: String = "",
    val sourceSampleRate: Int = 0,
    val sourceChannels: Int = 0,
    val sourceBitDepth: Int = 0,
    val outputSampleRate: Int = 0,
    val outputEncoding: String = "",
    val sharingMode: String = "",
    val performanceMode: String = "",
    val bufferFrames: Int = 0,
    val latencyMs: Double = 0.0,
    val xRuns: Int = 0,
    val bitPerfect: Boolean = false,
    val resampled: Boolean = false,
    val message: String = "",
)

enum class PlaybackState { IDLE, LOADING, PLAYING, PAUSED, ENDED, ERROR }

enum class AnalysisState { NONE, QUEUED, ANALYZING, READY, FAILED }

data class AppSettings(
    val bufferMs: Int = 300,
    val silenceThresholdDb: Float = -60f,
    val silenceMinDurationMs: Long = 2000L,
    val includeEdgeSilence: Boolean = false,
    val exclusiveMode: Boolean = true,
    val bitPerfectUsb: Boolean = false,
    val followPlayhead: Boolean = true,
)

object TimeFormat {
    fun timecode(ms: Long): String {
        if (ms < 0) return "0:00.000"
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = ms % 1000
        return "%d:%02d.%03d".format(minutes, seconds, millis)
    }

    fun short(ms: Long): String {
        if (ms < 0) return "0:00"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    fun duration(ms: Long): String {
        val totalSeconds = (ms + 500) / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
        else "%d:%02d".format(minutes, seconds)
    }

    fun parseTimecode(text: String): Long? {
        val trimmed = text.trim()
        val parts = trimmed.split(":")
        if (parts.isEmpty() || parts.size > 3) return null
        return try {
            val secondsPart = parts.last()
            val sec = secondsPart.substringBefore('.').toLongOrNull() ?: return null
            val frac = secondsPart.substringAfter('.', "").padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            var total = sec * 1000 + frac
            if (parts.size >= 2) total += (parts[parts.size - 2].toLongOrNull() ?: return null) * 60_000
            if (parts.size == 3) total += (parts[0].toLongOrNull() ?: return null) * 3_600_000
            total
        } catch (_: NumberFormatException) {
            null
        }
    }
}
