package com.wavecue.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * AudioTrack based output.
 *
 * Two modes:
 *  - shared float: portable fallback used when AAudio cannot open the requested rate.
 *  - USB bit-perfect: Android 14+ only, requires a USB output whose supported mixer
 *    attributes exactly match the source format, channel mask, and sample rate. In this
 *    mode the mixer neither mixes, resamples, nor applies volume; the samples are sent to
 *    the HAL as-is, so the app must keep its own volume at unity.
 */
class AudioTrackOutput private constructor(
    private val context: Context,
    private val track: AudioTrack,
    override val sampleRate: Int,
    override val channelCount: Int,
    private val encoding: Int,
    override val name: String,
    override val bitPerfect: Boolean,
    private val audioAttributes: AudioAttributes?,
    private val usbDevice: AudioDeviceInfo?,
) : AudioOutput {

    private val writtenSamples = AtomicLong(0)
    private val disconnected = AtomicBoolean(false)
    private var lastRawHead = 0L
    private var wrapOffset = 0L
    private var headBase = 0L

    /**
     * Scratch buffer reused across writes. [write] is called from the single engine
     * thread and AudioTrack copies the bytes before returning, so growing and rewinding
     * this buffer costs nothing per call and keeps the audio path allocation-free.
     */
    private var transferBuffer: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.LITTLE_ENDIAN)
    private val channelMask: Int =
        if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
    private val bytesPerSample: Int = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> 2
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
        AudioFormat.ENCODING_PCM_32BIT -> 4
        else -> 4
    }

    init {
        // Bit-perfect mixer attributes require unity app volume. This is also the
        // default for a fresh track; setting it explicitly keeps the guarantee.
        try {
            track.setVolume(1f)
        } catch (_: IllegalStateException) {
            // A released track reports the failure on the first write instead.
        }
    }

    override val bufferFrames: Int = track.bufferSizeInFrames.coerceAtLeast(0)
    override val capacityFrames: Int = bufferFrames
    override val latencyMs: Double = if (sampleRate > 0) bufferFrames * 1000.0 / sampleRate else 0.0
    override val sharingMode: String = if (bitPerfect) "bit-perfect" else "shared"
    override val performanceMode: String = "low-latency"
    override val outputEncoding: String = when (encoding) {
        AudioFormat.ENCODING_PCM_16BIT -> "int16"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "int24"
        AudioFormat.ENCODING_PCM_32BIT -> "int32"
        else -> "float32"
    }

    override fun start() {
        track.play()
    }

    override fun pause() {
        try {
            track.pause()
        } catch (_: IllegalStateException) {
            disconnected.set(true)
        }
    }

    @Synchronized
    override fun flush() {
        try {
            track.pause()
            track.flush()
            writtenSamples.set(0)
            // Baseline the counter at whatever it reads now. If the platform resets
            // it to zero asynchronously, [absoluteHead] rebases again.
            lastRawHead = rawHeadPosition()
            headBase = wrapOffset + lastRawHead
        } catch (_: IllegalStateException) {
            disconnected.set(true)
        }
    }

    override fun write(samples: FloatArray, sampleOffset: Int, sampleCount: Int): Int {
        if (sampleCount <= 0 || disconnected.get()) return 0
        val needed = sampleCount * bytesPerSample
        if (transferBuffer.capacity() < needed) {
            transferBuffer = ByteBuffer.allocateDirect(needed).order(ByteOrder.LITTLE_ENDIAN)
        }
        val buffer = transferBuffer
        buffer.clear()
        var i = sampleOffset
        val end = sampleOffset + sampleCount
        while (i < end) {
            val value = samples[i].coerceIn(-1f, 1f)
            when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT ->
                    buffer.putShort((value * 32768f).roundToInt().coerceIn(-32768, 32767).toShort())
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val v = (value * 8388608f).roundToInt().coerceIn(-8388608, 8388607)
                    buffer.put((v and 0xFF).toByte())
                    buffer.put(((v shr 8) and 0xFF).toByte())
                    buffer.put(((v shr 16) and 0xFF).toByte())
                }
                AudioFormat.ENCODING_PCM_32BIT -> {
                    val v = (value.toDouble() * 2147483648.0).toLong().coerceIn(-2147483648L, 2147483647L)
                    buffer.putInt(v.toInt())
                }
                else -> buffer.putFloat(value)
            }
            i++
        }
        buffer.flip()
        val written = try {
            track.write(buffer, buffer.limit(), AudioTrack.WRITE_NON_BLOCKING)
        } catch (_: IllegalStateException) {
            -1
        }
        if (written <= 0) {
            if (written == AudioTrack.ERROR_DEAD_OBJECT) disconnected.set(true)
            return 0
        }
        writtenSamples.addAndGet((written / bytesPerSample).toLong())
        return written / bytesPerSample
    }

    @Synchronized
    override fun positionFrames(): Long {
        val head = absoluteHead()
        return (head - headBase).coerceAtLeast(0)
    }

    /**
     * Extends the 32-bit playback head to a monotonic frame counter. A backwards jump
     * is either a genuine wrap (very long session) or the counter being reset by a
     * flush; in the latter case the session baseline is rebased so position restarts
     * from zero instead of reporting a bogus offset.
     */
    @Synchronized
    private fun absoluteHead(): Long {
        val raw = rawHeadPosition()
        if (raw < lastRawHead) {
            if (lastRawHead - raw > 0x8000_0000L) {
                wrapOffset += 0x1_0000_0000L
            } else {
                headBase = wrapOffset + raw
            }
        }
        lastRawHead = raw
        return wrapOffset + raw
    }

    private fun rawHeadPosition(): Long = try {
        track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
    } catch (_: IllegalStateException) {
        0L
    }

    override fun bufferedFrames(): Int {
        val writtenFrames = writtenSamples.get() / channelCount
        val head = positionFrames()
        return (writtenFrames - head).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
    }

    override fun xRuns(): Int = 0
    override fun isDisconnected(): Boolean = disconnected.get()

    override fun close() {
        try {
            track.pause()
            track.flush()
        } catch (_: IllegalStateException) {
            // already stopped
        }
        try {
            track.release()
        } catch (_: IllegalStateException) {
            // ignore
        }
        if (bitPerfect && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val attributes = audioAttributes
            val device = usbDevice
            if (attributes != null && device != null) {
                val manager = context.getSystemService(AudioManager::class.java)
                try {
                    manager?.clearPreferredMixerAttributes(attributes, device)
                } catch (_: Exception) {
                    // the device may already be gone
                }
            }
        }
    }

    companion object {
        private val USB_TYPES = setOf(
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )

        fun createSharedFloat(context: Context, sampleRate: Int, channelCount: Int): AudioTrackOutput? {
            if (channelCount !in 1..2) return null
            val mask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val encoding = AudioFormat.ENCODING_PCM_FLOAT
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
            if (minBuffer <= 0) return null
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            return try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(format)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(minBuffer * 2)
                    .build()
                if (track.state != AudioTrack.STATE_INITIALIZED) {
                    track.release()
                    null
                } else {
                    AudioTrackOutput(context, track, sampleRate, channelCount, encoding, "AudioTrack shared", false, attributes, null)
                }
            } catch (_: Exception) {
                null
            }
        }

        fun createBitPerfect(
            context: Context,
            sampleRate: Int,
            channelCount: Int,
            sourceBitDepth: Int,
            sourceIsFloat: Boolean,
        ): AudioTrackOutput? {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
            if (channelCount !in 1..2) return null
            val encoding = when {
                sourceBitDepth == 16 -> AudioFormat.ENCODING_PCM_16BIT
                sourceBitDepth == 24 -> AudioFormat.ENCODING_PCM_24BIT_PACKED
                sourceBitDepth == 32 && sourceIsFloat -> AudioFormat.ENCODING_PCM_FLOAT
                else -> return null
            }
            val manager = context.getSystemService(AudioManager::class.java) ?: return null
            val usb = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type in USB_TYPES } ?: return null

            val mask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
            val desired = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(mask)
                .build()
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val mixerAttributes = try {
                manager.getSupportedMixerAttributes(usb).firstOrNull { candidate ->
                    candidate.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT &&
                        candidate.format == desired
                }
            } catch (_: Exception) {
                null
            } ?: return null

            val accepted = try {
                manager.setPreferredMixerAttributes(attributes, usb, mixerAttributes)
            } catch (_: Exception) {
                false
            }
            if (!accepted) return null

            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, encoding)
            val bytesPerFrame = channelCount * when (encoding) {
                AudioFormat.ENCODING_PCM_16BIT -> 2
                AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                else -> 4
            }
            val bufferBytes = if (minBuffer > 0) minBuffer * 2 else sampleRate * bytesPerFrame
            val track = try {
                AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(desired)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            } catch (_: Exception) {
                try {
                    manager.clearPreferredMixerAttributes(attributes, usb)
                } catch (_: Exception) {
                    // ignore
                }
                return null
            }
            track.preferredDevice = usb

            if (track.state != AudioTrack.STATE_INITIALIZED) {
                track.release()
                try {
                    manager.clearPreferredMixerAttributes(attributes, usb)
                } catch (_: Exception) {
                    // ignore
                }
                return null
            }

            return AudioTrackOutput(
                context = context,
                track = track,
                sampleRate = sampleRate,
                channelCount = channelCount,
                encoding = encoding,
                name = "USB bit-perfect",
                bitPerfect = true,
                audioAttributes = attributes,
                usbDevice = usb,
            )
        }
    }
}
