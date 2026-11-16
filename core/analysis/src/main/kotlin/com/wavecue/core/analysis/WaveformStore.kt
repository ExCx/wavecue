package com.wavecue.core.analysis

import android.content.Context
import java.io.File

class WaveformStore(private val context: Context) {
    private val directory: File
        get() = File(context.filesDir, "waveforms").apply { mkdirs() }

    fun fileFor(trackId: Long): File = File(directory, "$trackId.wfc")

    fun delete(trackId: Long) {
        fileFor(trackId).delete()
    }

    fun clear() {
        directory.listFiles()?.forEach { it.delete() }
    }
}
