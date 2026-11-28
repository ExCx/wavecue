package com.wavecue.core.audio

import android.content.Context
import android.net.Uri
import java.io.FileInputStream

object PcmSourceFactory {
    private val wavExtensions = setOf("wav", "wave", "bwf")

    fun isWav(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in wavExtensions
    }

    /**
     * Sniffs the RIFF/RF64/BW64 header so a WAV is always decoded by WavPcmSource,
     * even when the display name has no extension (e.g. MediaStore TITLE metadata
     * or a per-file pick where the name is a content URI segment).
     */
    private fun hasWavMagic(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                FileInputStream(pfd.fileDescriptor).use { stream ->
                    val header = ByteArray(12)
                    if (stream.read(header) < 12) return false
                    val riff = String(header, 0, 4, Charsets.US_ASCII)
                    val wave = String(header, 8, 4, Charsets.US_ASCII)
                    (riff == "RIFF" || riff == "RF64" || riff == "BW64") && wave == "WAVE"
                }
            } ?: false
        } catch (_: Exception) {
            false
        }
    }

    fun open(context: Context, uri: Uri, displayName: String): PcmSource {
        return if (isWav(displayName) || hasWavMagic(context, uri)) {
            WavPcmSource.open(context, uri)
        } else {
            Mp3PcmSource.open(context, uri)
        }
    }
}
