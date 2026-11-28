package com.wavecue.core.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MP3 reader built on MediaExtractor + MediaCodec. Seeking uses the extractor's
 * previous-sync point and then trims decoded frames against presentation timestamps,
 * so flags land on the intended sample instead of the nearest MP3 frame.
 */
class Mp3PcmSource private constructor(
    private val pfd: ParcelFileDescriptor,
    private val extractor: MediaExtractor,
    private val decoder: MediaCodec,
    initialRate: Int,
    initialChannels: Int,
    private val durationUs: Long,
    private val mime: String,
) : PcmSource {

    private var actualRate = initialRate
    private var actualChannels = initialChannels
    private var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
    private var bytesPerSample = 2
    private var bytesPerFrame = initialChannels * 2

    private val info = MediaCodec.BufferInfo()
    private var inputEnded = false
    private var outputEnded = false
    private var seekTargetFrame = -1L

    override val sampleRate: Int get() = actualRate
    override val channelCount: Int get() = actualChannels
    override val bitDepth: Int get() = 16
    override val isFloat: Boolean get() = false
    override val codec: String get() = if (mime.contains("mpeg")) "MP3" else mime

    override val durationFrames: Long
        get() = if (actualRate > 0 && durationUs > 0) durationUs * actualRate / 1_000_000 else 0

    override val durationMs: Long get() = durationUs / 1000

    override fun seekToFrame(frame: Long) {
        val duration = durationFrames
        val target = if (duration > 0) frame.coerceIn(0, duration) else frame.coerceAtLeast(0)
        val targetUs = target * 1_000_000L / actualRate.coerceAtLeast(1)
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        decoder.flush()
        inputEnded = false
        outputEnded = false
        seekTargetFrame = target
    }

    @Suppress("DEPRECATION") // INFO_OUTPUT_BUFFERS_CHANGED is still a legal result code.
    override fun readFloat(buffer: FloatArray, frameCount: Int): Int {
        if (outputEnded) return 0
        val maxFrames = minOf(frameCount, buffer.size / actualChannels)
        if (maxFrames <= 0) return 0

        var spins = 0
        while (spins++ < 256) {
            feedInput()
            val index = try {
                decoder.dequeueOutputBuffer(info, 5_000)
            } catch (_: IllegalStateException) {
                return 0
            }
            when {
                index >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    var produced = 0
                    if (info.size > 0) {
                        val out = decoder.getOutputBuffer(index)
                        if (out != null) {
                            out.position(info.offset)
                            out.limit(info.offset + info.size)
                            produced = convert(out, buffer, maxFrames)
                        }
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (eos) outputEnded = true
                    if (produced > 0) return produced
                    if (outputEnded) return 0
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateOutputFormat()
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (inputEnded && outputEnded) return 0
                }
                else -> return 0
            }
        }
        return 0
    }

    private fun feedInput() {
        if (inputEnded) return
        val index = try {
            decoder.dequeueInputBuffer(5_000)
        } catch (_: IllegalStateException) {
            return
        }
        if (index < 0) return
        val input = decoder.getInputBuffer(index) ?: return
        val size = try {
            extractor.readSampleData(input, 0)
        } catch (_: Exception) {
            -1
        }
        if (size < 0) {
            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEnded = true
        } else {
            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun convert(out: ByteBuffer, buffer: FloatArray, maxFrames: Int): Int {
        val framesInBuffer = info.size / bytesPerFrame
        if (framesInBuffer <= 0) return 0

        var skip = 0
        if (seekTargetFrame >= 0) {
            val startFrame = info.presentationTimeUs * actualRate / 1_000_000L
            if (startFrame + framesInBuffer <= seekTargetFrame) return 0
            skip = (seekTargetFrame - startFrame).coerceIn(0, framesInBuffer.toLong()).toInt()
            seekTargetFrame = -1
        }

        val available = framesInBuffer - skip
        val framesToCopy = minOf(available, maxFrames)
        if (framesToCopy <= 0) return 0

        out.order(ByteOrder.LITTLE_ENDIAN)
        val channels = actualChannels
        val outSamples = framesToCopy * channels
        var bytePos = out.position() + skip * bytesPerFrame

        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val floats = out.asFloatBuffer()
                floats.position(skip * channels)
                floats.get(buffer, 0, outSamples)
                for (i in 0 until outSamples) {
                    if (!buffer[i].isFinite()) buffer[i] = 0f
                }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                var i = 0
                while (i < outSamples) {
                    val v32 = ((out.get(bytePos).toInt() and 0xFF) shl 8) or
                        ((out.get(bytePos + 1).toInt() and 0xFF) shl 16) or
                        ((out.get(bytePos + 2).toInt() and 0xFF) shl 24)
                    buffer[i++] = (v32 / 2147483648.0).toFloat()
                    bytePos += 3
                }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                var i = 0
                while (i < outSamples) {
                    val v = (out.get(bytePos).toInt() and 0xFF) or
                        ((out.get(bytePos + 1).toInt() and 0xFF) shl 8) or
                        ((out.get(bytePos + 2).toInt() and 0xFF) shl 16) or
                        (out.get(bytePos + 3).toInt() shl 24)
                    buffer[i++] = (v / 2147483648.0).toFloat()
                    bytePos += 4
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                var i = 0
                while (i < outSamples) {
                    buffer[i++] = ((out.get(bytePos).toInt() and 0xFF) - 128) / 128f
                    bytePos += 1
                }
            }
            else -> {
                val shorts = out.asShortBuffer()
                shorts.position(skip * channels)
                var i = 0
                while (i < outSamples) {
                    buffer[i++] = shorts.get() / 32768f
                }
            }
        }
        return framesToCopy
    }

    private fun updateOutputFormat() {
        val format = try {
            decoder.outputFormat
        } catch (_: IllegalStateException) {
            return
        }
        val rate = format.intOr(MediaFormat.KEY_SAMPLE_RATE, actualRate)
        if (rate > 0) actualRate = rate
        val channels = format.intOr(MediaFormat.KEY_CHANNEL_COUNT, actualChannels)
        if (channels > 0) actualChannels = channels
        pcmEncoding = format.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        bytesPerSample = bytesForEncoding(pcmEncoding)
        bytesPerFrame = actualChannels * bytesPerSample
    }

    /**
     * MediaFormat#getInteger(String, Int) only exists on API 29+, while the
     * single-argument read works back to the minimum supported level.
     */
    @Suppress("DEPRECATION")
    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback

    override fun close() {
        try {
            decoder.stop()
        } catch (_: Exception) {
            // ignore
        }
        try {
            decoder.release()
        } catch (_: Exception) {
            // ignore
        }
        try {
            extractor.release()
        } catch (_: Exception) {
            // ignore
        }
        try {
            pfd.close()
        } catch (_: Exception) {
            // ignore
        }
    }

    companion object {
        private fun bytesForEncoding(encoding: Int): Int = when (encoding) {
            AudioFormat.ENCODING_PCM_8BIT -> 1
            AudioFormat.ENCODING_PCM_16BIT -> 2
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            AudioFormat.ENCODING_PCM_32BIT,
            AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> 2
        }

        fun open(context: Context, uri: Uri): Mp3PcmSource {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: throw IOException("Cannot open file descriptor for $uri")
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(pfd.fileDescriptor, 0, pfd.statSize)
                var trackIndex = -1
                var format: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(i)
                    val mime = candidate.getString(MediaFormat.KEY_MIME).orEmpty()
                    if (mime.startsWith("audio/")) {
                        trackIndex = i
                        format = candidate
                        break
                    }
                }
                if (trackIndex < 0 || format == null) throw IOException("No audio track found")
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
                val selectedFormat = format
                if (rate <= 0 || channels !in 1..2) {
                    throw IOException("Unsupported MP3 format: $rate Hz, $channels ch")
                }
                extractor.selectTrack(trackIndex)
                // Prefer IEEE float output so decoding is bit-depth agnostic;
                // fall back to the format's native encoding if unsupported.
                if (selectedFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                    try {
                        selectedFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                    } catch (_: Exception) { }
                }
                val newDecoder = MediaCodec.createDecoderByType(mime)
                decoder = newDecoder
                newDecoder.configure(selectedFormat, null, null, 0)
                newDecoder.start()
                return Mp3PcmSource(pfd, extractor, newDecoder, rate, channels, durationUs, mime)
            } catch (t: Throwable) {
                try {
                    decoder?.release()
                } catch (_: Exception) {
                    // ignore
                }
                try {
                    extractor?.release()
                } catch (_: Exception) {
                    // ignore
                }
                try {
                    pfd.close()
                } catch (_: Exception) {
                    // ignore
                }
                throw if (t is IOException) t else IOException("Cannot open MP3: ${t.message}", t)
            }
        }
    }
}
