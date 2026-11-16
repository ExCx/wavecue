package com.wavecue.core.audio

import java.io.Closeable

/**
 * Thin JNI wrapper around a native AAudio output stream. The native callback is the
 * only consumer of the ring buffer; no lock, allocation, or JVM call happens there.
 */
class NativeAudioSink private constructor(private val handle: Long) : Closeable {

    private var closed = false

    private external fun nativeStart(handle: Long): Boolean
    private external fun nativePause(handle: Long)
    private external fun nativeFlush(handle: Long)
    private external fun nativeWrite(handle: Long, samples: FloatArray, sampleOffset: Int, sampleCount: Int): Int
    private external fun nativeDestroy(handle: Long)
    private external fun nativePositionFrames(handle: Long): Long
    private external fun nativeDeliveredFrames(handle: Long): Long
    private external fun nativeBufferedFrames(handle: Long): Int
    private external fun nativeGetSampleRate(handle: Long): Int
    private external fun nativeGetChannels(handle: Long): Int
    private external fun nativeGetSharingMode(handle: Long): Int
    private external fun nativeGetPerformanceMode(handle: Long): Int
    private external fun nativeGetBufferFrames(handle: Long): Int
    private external fun nativeGetCapacityFrames(handle: Long): Int
    private external fun nativeGetBurstFrames(handle: Long): Int
    private external fun nativeGetXRunCount(handle: Long): Int
    private external fun nativeIsDisconnected(handle: Long): Boolean

    fun start(): Boolean = nativeStart(handle)
    fun pause() = nativePause(handle)
    fun flush() = nativeFlush(handle)
    fun write(samples: FloatArray, sampleOffset: Int, sampleCount: Int): Int =
        nativeWrite(handle, samples, sampleOffset, sampleCount)

    fun positionFrames(): Long = nativePositionFrames(handle)
    fun deliveredFrames(): Long = nativeDeliveredFrames(handle)
    fun bufferedFrames(): Int = nativeBufferedFrames(handle)
    fun sampleRate(): Int = nativeGetSampleRate(handle)
    fun channels(): Int = nativeGetChannels(handle)
    fun sharingMode(): Int = nativeGetSharingMode(handle)
    fun performanceMode(): Int = nativeGetPerformanceMode(handle)
    fun bufferFrames(): Int = nativeGetBufferFrames(handle)
    fun capacityFrames(): Int = nativeGetCapacityFrames(handle)
    fun burstFrames(): Int = nativeGetBurstFrames(handle)
    fun xRuns(): Int = nativeGetXRunCount(handle)
    fun isDisconnected(): Boolean = nativeIsDisconnected(handle)

    override fun close() {
        if (closed) return
        closed = true
        nativeDestroy(handle)
    }

    companion object {
        init {
            System.loadLibrary("wavecue_audio")
        }

        private const val SHARING_EXCLUSIVE = 1
        private const val SHARING_SHARED = 2
        private const val PERF_NONE = 0
        private const val PERF_LOW_LATENCY = 1
        private const val PERF_POWER_SAVING = 2

        @JvmStatic
        private external fun nativeCreate(sampleRate: Int, channels: Int, exclusive: Boolean): Long

        fun create(sampleRate: Int, channels: Int, exclusive: Boolean): NativeAudioSink? {
            val handle = nativeCreate(sampleRate, channels, exclusive)
            return if (handle != 0L) NativeAudioSink(handle) else null
        }

        fun sharingName(mode: Int): String = when (mode) {
            SHARING_EXCLUSIVE -> "exclusive"
            SHARING_SHARED -> "shared"
            else -> "unknown"
        }

        fun performanceName(mode: Int): String = when (mode) {
            PERF_LOW_LATENCY -> "low-latency"
            PERF_POWER_SAVING -> "power-saving"
            PERF_NONE -> "none"
            else -> "unknown"
        }
    }
}
