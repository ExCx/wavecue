package com.wavecue.core.audio

import java.io.Closeable

/**
 * Sequential, seekable PCM reader. Implementations always hand out interleaved
 * float samples so the playback engine and the analyser share one code path.
 */
interface PcmSource : Closeable {
    val sampleRate: Int
    val channelCount: Int

    /** Source bit depth. 32 with [isFloat] means IEEE float. */
    val bitDepth: Int
    val isFloat: Boolean
    val codec: String
    val durationFrames: Long
    val durationMs: Long

    fun seekToFrame(frame: Long)

    /** Reads up to [frameCount] interleaved frames. Returns frames read, 0 at EOF. */
    fun readFloat(buffer: FloatArray, frameCount: Int): Int
}
