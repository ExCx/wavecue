package com.wavecue.core.audio

import java.io.IOException

/**
 * Random-access view over the bytes of a WAV container. Keeping the header parser
 * behind this interface means it never touches Android or a real file, so the same
 * code that reads device audio is exercised by plain JVM unit tests.
 */
interface WavByteSource {
    val size: Long
    /** Reads up to [length] bytes at [offset]; returns fewer bytes only at end of file. */
    fun read(offset: Long, length: Int): ByteArray
}

/** Parsed WAV metadata plus the location of the sample payload. */
data class WavHeader(
    val formatTag: Int,
    val channels: Int,
    val sampleRate: Int,
    val blockAlign: Int,
    val bitsPerSample: Int,
    val validBitsPerSample: Int = bitsPerSample,
    val isFloat: Boolean,
    val dataOffset: Long,
    val dataSize: Long,
) {
    /** Container bytes per sample, derived from block align (handles 24-in-32 padding). */
    val bytesPerSample: Int get() = if (channels > 0) blockAlign / channels else (bitsPerSample + 7) / 8
    val durationFrames: Long get() = if (blockAlign > 0) dataSize / blockAlign else 0
    val durationMs: Long get() = if (sampleRate > 0) durationFrames * 1000 / sampleRate else 0
}

/**
 * RIFF/RF64/BW64 header parser. Supports WAVE_FORMAT_EXTENSIBLE, PCM 8/16/24/32-bit
 * and 32-bit IEEE float. Unknown chunks are skipped using their declared size, and
 * odd-sized chunks are word-aligned as the specification requires.
 */
object WavHeaderParser {
    const val WAVE_FORMAT_PCM = 0x0001
    const val WAVE_FORMAT_IEEE_FLOAT = 0x0003
    const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

    fun parse(source: WavByteSource): WavHeader {
        val fileSize = source.size
        if (fileSize < 12) throw IOException("File is too small to be a WAV file")

        val header = source.read(0, 12)
        if (header.size < 12) throw IOException("Truncated RIFF header")
        val riff = String(header, 0, 4, Charsets.US_ASCII)
        val wave = String(header, 8, 4, Charsets.US_ASCII)
        if ((riff != "RIFF" && riff != "RF64" && riff != "BW64") || wave != "WAVE") {
            throw IOException("Not a RIFF/WAVE file")
        }

        var format: WavHeader? = null
        var dataOffset = -1L
        var dataSize = -1L
        var ds64DataSize = -1L
        var position = 12L

        while (position + 8 <= fileSize) {
            val chunkHeader = source.read(position, 8)
            if (chunkHeader.size < 8) break
            val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val size = readInt32(chunkHeader, 4).toLong() and 0xFFFFFFFFL
            val body = position + 8

            when (id) {
                "ds64" -> {
                    // The 64-bit data length is the second 64-bit field of the ds64 chunk.
                    if (size >= 24) {
                        val bytes = source.read(body, 24)
                        if (bytes.size >= 24) ds64DataSize = readInt64(bytes, 8)
                    }
                }
                "fmt " -> {
                    if (size < 16) throw IOException("Invalid fmt chunk")
                    val length = minOf(size, 64L).toInt()
                    val bytes = source.read(body, length)
                    if (bytes.size < 16) throw IOException("Truncated fmt chunk")
                    format = parseFormat(bytes)
                }
                "data" -> {
                    dataOffset = body
                    dataSize = if (size == 0xFFFFFFFFL) -1 else size
                }
            }

            if (size > fileSize) break
            position = body + size + (size and 1L)
        }

        val parsedFormat = format ?: throw IOException("Missing fmt chunk")
        if (dataOffset < 0) throw IOException("Missing data chunk")
        if (parsedFormat.blockAlign <= 0) throw IOException("Invalid block alignment")

        var effectiveDataSize = dataSize
        if (effectiveDataSize < 0 && ds64DataSize >= 0) effectiveDataSize = ds64DataSize
        if (effectiveDataSize < 0) effectiveDataSize = (fileSize - dataOffset).coerceAtLeast(0)
        effectiveDataSize = minOf(effectiveDataSize, fileSize - dataOffset).coerceAtLeast(0)

        return parsedFormat.copy(dataOffset = dataOffset, dataSize = effectiveDataSize)
    }

    private fun parseFormat(bytes: ByteArray): WavHeader {
        val rawTag = readInt16(bytes, 0)
        var tag = rawTag
        val channels = readInt16(bytes, 2)
        val sampleRate = readInt32(bytes, 4)
        val blockAlign = readInt16(bytes, 12)
        val bits = readInt16(bytes, 14)
        var validBits = bits

        if (rawTag == WAVE_FORMAT_EXTENSIBLE && bytes.size >= 40) {
            // wValidBitsPerSample at offset 18; SubFormat GUID code at offset 24.
            val extValidBits = readInt16(bytes, 18)
            if (extValidBits in 1..bits) validBits = extValidBits
            tag = readInt16(bytes, 24)
        }

        if (channels !in 1..8) throw IOException("Unsupported channel count: $channels")
        if (sampleRate <= 0) throw IOException("Invalid sample rate: $sampleRate")
        val isFloat = tag == WAVE_FORMAT_IEEE_FLOAT
        if (tag != WAVE_FORMAT_PCM && !isFloat) {
            throw IOException("Unsupported WAV encoding: 0x%04X".format(tag))
        }
        if (bits !in intArrayOf(8, 16, 24, 32, 64)) {
            throw IOException("Unsupported WAV bit depth: $bits")
        }

        val computedAlign = channels * ((bits + 7) / 8)
        val effectiveAlign = if (blockAlign >= computedAlign && blockAlign % channels == 0) {
            blockAlign
        } else {
            computedAlign
        }
        val bytesPerSample = effectiveAlign / channels
        if ((!isFloat && bytesPerSample !in 1..4) || (isFloat && bytesPerSample != 4 && bytesPerSample != 8)) {
            throw IOException("Unsupported WAV sample container size: $bytesPerSample bytes")
        }

        return WavHeader(
            formatTag = tag,
            channels = channels,
            sampleRate = sampleRate,
            blockAlign = effectiveAlign,
            bitsPerSample = bits,
            validBitsPerSample = validBits.coerceIn(1, bytesPerSample * 8),
            isFloat = isFloat,
            dataOffset = 0,
            dataSize = 0,
        )
    }

    private fun readInt16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)

    private fun readInt64(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }
}
