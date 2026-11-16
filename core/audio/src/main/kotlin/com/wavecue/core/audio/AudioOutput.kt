package com.wavecue.core.audio

import java.io.Closeable

/**
 * Low-level audio output. All implementations accept interleaved float samples;
 * conversion to the device encoding is the sink's responsibility.
 */
interface AudioOutput : Closeable {
    val name: String
    val sampleRate: Int
    val channelCount: Int

    /** Frames currently held by the platform buffer. */
    val bufferFrames: Int

    /** Total frames the sink can hold before the writer must wait. */
    val capacityFrames: Int
    val latencyMs: Double
    val sharingMode: String
    val performanceMode: String
    val outputEncoding: String
    val bitPerfect: Boolean

    fun start()
    fun pause()
    fun flush()

    /**
     * Writes interleaved float samples. Returns the number of samples accepted,
     * which may be smaller than [sampleCount] or zero when the sink is full.
     */
    fun write(samples: FloatArray, sampleOffset: Int, sampleCount: Int): Int

    fun positionFrames(): Long
    fun bufferedFrames(): Int
    fun xRuns(): Int
    fun isDisconnected(): Boolean
}
