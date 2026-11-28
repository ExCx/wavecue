package com.wavecue.core.audio

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Streaming WAV reader. Header parsing lives in [WavHeaderParser] so it can be unit
 * tested; this class only performs the file I/O and sample conversion. The decoded
 * float values are exact representations of the integer samples (24-bit integers
 * fit in a float mantissa).
 */
class WavPcmSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val fileStream: FileInputStream,
    private val channel: FileChannel,
    private val header: WavHeader,
) : PcmSource {

    override val sampleRate: Int = header.sampleRate
    override val channelCount: Int = header.channels
    override val bitDepth: Int = header.validBitsPerSample
    override val isFloat: Boolean = header.isFloat
    override val codec: String = if (header.isFloat) "WAV float" else "WAV PCM"
    override val durationFrames: Long = header.durationFrames
    override val durationMs: Long = header.durationMs

    private val blockAlign = header.blockAlign
    private val dataOffset = header.dataOffset
    private var framePosition = 0L
    private var byteBuffer = ByteArray(0)

    override fun seekToFrame(frame: Long) {
        val clamped = frame.coerceIn(0, durationFrames)
        channel.position(dataOffset + clamped * blockAlign)
        framePosition = clamped
    }

    override fun readFloat(buffer: FloatArray, frameCount: Int): Int {
        val channels = channelCount
        val maxFrames = minOf(frameCount, buffer.size / channels)
        val remaining = durationFrames - framePosition
        if (remaining <= 0 || maxFrames <= 0) return 0
        val frames = minOf(maxFrames.toLong(), remaining).toInt()
        val bytesNeeded = frames * blockAlign

        if (byteBuffer.size < bytesNeeded) {
            byteBuffer = ByteArray(bytesNeeded)
        }

        var offset = 0
        while (offset < bytesNeeded) {
            val read = channel.read(ByteBuffer.wrap(byteBuffer, offset, bytesNeeded - offset), dataOffset + framePosition * blockAlign + offset.toLong())
            if (read < 0) break
            offset += read
        }
        val framesRead = offset / blockAlign
        if (framesRead <= 0) return 0

        decode(byteBuffer, framesRead, buffer)
        framePosition += framesRead
        return framesRead
    }

    private fun decode(bytes: ByteArray, frames: Int, out: FloatArray) {
        decodeWavSamples(
            bytes = bytes,
            frames = frames,
            channels = channelCount,
            bytesPerSample = header.bytesPerSample,
            validBits = header.validBitsPerSample,
            isFloat = header.isFloat,
            framePadding = blockAlign - (channelCount * header.bytesPerSample),
            out = out,
        )
    }

    override fun close() {
        try {
            fileStream.close()
        } catch (_: Exception) {
            // ignore
        }
        try {
            pfd.close()
        } catch (_: Exception) {
            // ignore
        }
    }

    private class FileChannelByteSource(
        private val channel: FileChannel,
    ) : WavByteSource {
        override val size: Long
            get() = channel.size()

        override fun read(offset: Long, length: Int): ByteArray {
            val available = (size - offset).coerceAtLeast(0).coerceAtMost(length.toLong()).toInt()
            if (available <= 0) return ByteArray(0)
            val bytes = ByteArray(available)
            var read = 0
            while (read < available) {
                val n = channel.read(ByteBuffer.wrap(bytes, read, available - read), offset + read)
                if (n < 0) break
                read += n
            }
            return if (read == available) bytes else bytes.copyOf(read)
        }
    }

    companion object {
        fun open(context: Context, uri: Uri): WavPcmSource {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open file descriptor for $uri")
            var stream: FileInputStream? = null
            try {
                stream = FileInputStream(pfd.fileDescriptor)
                val channel = stream.channel
                val header = WavHeaderParser.parse(FileChannelByteSource(channel))
                return WavPcmSource(
                    pfd = pfd,
                    fileStream = stream,
                    channel = channel,
                    header = header,
                )
            } catch (t: Throwable) {
                try {
                    stream?.close()
                } catch (_: Exception) {
                    // ignore
                }
                try {
                    pfd.close()
                } catch (_: Exception) {
                    // ignore
                }
                throw if (t is IOException) t else IOException("Invalid WAV file: ${t.message}", t)
            }
        }

        private fun readInt16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

        private fun readInt32(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }
}

/**
 * Byte-exact WAV sample conversion, shared by playback and analysis and exposed
 * for regression tests. Handles packed (3-byte) and padded (4-byte) 24-bit PCM,
 * all integer widths, and IEEE float.
 */
internal fun decodeWavSamples(
    bytes: ByteArray,
    frames: Int,
    channels: Int,
    bytesPerSample: Int,
    validBits: Int,
    isFloat: Boolean,
    framePadding: Int,
    out: FloatArray,
) {
    var byteIndex = 0
    var sampleIndex = 0

    when {
        isFloat && bytesPerSample == 4 -> {
            for (f in 0 until frames) {
                for (c in 0 until channels) {
                    val v = Float.fromBits(readInt32LE(bytes, byteIndex))
                    out[sampleIndex++] = if (v.isFinite()) v else 0f
                    byteIndex += 4
                }
                byteIndex += framePadding
            }
        }
        isFloat && bytesPerSample == 8 -> {
            for (f in 0 until frames) {
                for (c in 0 until channels) {
                    val lo = readInt32LE(bytes, byteIndex).toLong() and 0xFFFFFFFFL
                    val hi = readInt32LE(bytes, byteIndex + 4).toLong()
                    val v = Double.fromBits(lo or (hi shl 32)).toFloat()
                    out[sampleIndex++] = if (v.isFinite()) v else 0f
                    byteIndex += 8
                }
                byteIndex += framePadding
            }
        }
        bytesPerSample == 1 -> {
            for (f in 0 until frames) {
                for (c in 0 until channels) {
                    out[sampleIndex++] = ((bytes[byteIndex].toInt() and 0xFF) - 128) / 128f
                    byteIndex += 1
                }
                byteIndex += framePadding
            }
        }
        bytesPerSample == 2 -> {
            for (f in 0 until frames) {
                for (c in 0 until channels) {
                    val v = (bytes[byteIndex].toInt() and 0xFF) or
                        (bytes[byteIndex + 1].toInt() shl 8)
                    out[sampleIndex++] = v / 32768f
                    byteIndex += 2
                }
                byteIndex += framePadding
            }
        }
        bytesPerSample == 3 -> {
            for (f in 0 until frames) {
                for (c in 0 until channels) {
                    val v32 = ((bytes[byteIndex].toInt() and 0xFF) shl 8) or
                        ((bytes[byteIndex + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[byteIndex + 2].toInt() and 0xFF) shl 24)
                    out[sampleIndex++] = (v32 / 2147483648.0).toFloat()
                    byteIndex += 3
                }
                byteIndex += framePadding
            }
        }
        bytesPerSample == 4 -> {
            // Integer container. Scale by the number of valid bits so a
            // 24-in-32 sample (valid data in the low 24 bits) normalises to
            // the same range as a packed 24-bit sample.
            val scale = (1L shl (validBits.coerceIn(1, 31) - 1)).toDouble()
            for (f in 0 until frames) {
                for (c in 0 until channels) {
                    val v = readInt32LE(bytes, byteIndex)
                    out[sampleIndex++] = (v / scale).toFloat().coerceIn(-1f, 1f)
                    byteIndex += 4
                }
                byteIndex += framePadding
            }
        }
        else -> throw IOException("Unsupported WAV bytes per sample: $bytesPerSample")
    }
}

internal fun readInt32LE(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
