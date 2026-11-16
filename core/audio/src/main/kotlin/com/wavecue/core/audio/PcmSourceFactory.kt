package com.wavecue.core.audio

import android.content.Context
import android.net.Uri

object PcmSourceFactory {
    private val wavExtensions = setOf("wav", "wave", "bwf")

    fun isWav(name: String): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase()
        return extension in wavExtensions
    }

    fun open(context: Context, uri: Uri, displayName: String): PcmSource {
        return if (isWav(displayName)) {
            WavPcmSource.open(context, uri)
        } else {
            Mp3PcmSource.open(context, uri)
        }
    }
}
