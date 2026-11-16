package com.wavecue.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class WavHeaderParserTest {

    private class ByteArraySource(private val bytes: ByteArray) : WavByteSource {
        override val size: Long get() = bytes.size.toLong()

        override fun read(offset: Long, length: Int): ByteArray {
            if (offset < 0 || offset >= bytes.size) return ByteArray(0)
            val from = offset.toInt()
            val to = minOf(bytes.size, from + length)
            return bytes.copyOfRange(from, to)
        }
    }

    @Test
    fun parsesClassicPcmStereo() {
        val header = parse(
            wav(
                formatTag = WavHeaderParser.WAVE_FORMAT_PCM,
                channels = 2,
                sampleRate = 44_100,
                bits = 16,
                dataBytes = 4_410 * 4,
            )
        )

        assertEquals(2, header.channels)
        assertEquals(44_100, header.sampleRate)
        assertEquals(16, header.bitsPerSample)
        assertEquals(4, header.blockAlign)
        assertFalse(header.isFloat)
        assertEquals(4_410L, header.durationFrames)
        assertEquals(100L, header.durationMs)
    }

    @Test
    fun parsesExtensibleSubFormat() {
        val header = parse(
            wav(
                formatTag = WavHeaderParser.WAVE_FORMAT_EXTENSIBLE,
                subFormat = WavHeaderParser.WAVE_FORMAT_PCM,
                channels = 1,
                sampleRate = 48_000,
                bits = 24,
                dataBytes = 6,
                fmtChunkSize = 40,
            )
        )

        assertEquals(WavHeaderParser.WAVE_FORMAT_PCM, header.formatTag)
        assertEquals(1, header.channels)
        assertEquals(24, header.bitsPerSample)
        assertEquals(3, header.blockAlign)
        assertFalse(header.isFloat)
        assertEquals(2L, header.durationFrames)
    }

    @Test
    fun parsesFloatFormat() {
        val header = parse(
            wav(
                formatTag = WavHeaderParser.WAVE_FORMAT_IEEE_FLOAT,
                channels = 2,
                sampleRate = 96_000,
                bits = 32,
                dataBytes = 32,
            )
        )

        assertTrue(header.isFloat)
        assertEquals(32, header.bitsPerSample)
        assertEquals(8, header.blockAlign)
        assertEquals(4L, header.durationFrames)
    }

    @Test
    fun readsRf64DataSizeFromDs64Chunk() {
        val header = parse(
            wav(
                riffId = "RF64",
                formatTag = WavHeaderParser.WAVE_FORMAT_PCM,
                channels = 2,
                sampleRate = 48_000,
                bits = 24,
                dataBytes = 12,
                dataDeclaredSize = 0xFFFFFFFF.toInt(),
                ds64DataSize = 12L,
            )
        )

        assertEquals(12L, header.dataSize)
        assertEquals(2L, header.durationFrames)
    }

    @Test
    fun skipsUnknownChunksWithOddSizePadding() {
        val header = parse(
            wav(
                formatTag = WavHeaderParser.WAVE_FORMAT_PCM,
                channels = 2,
                sampleRate = 44_100,
                bits = 16,
                dataBytes = 16,
                extraChunks = listOf(chunk("junk", byteArrayOf(1, 2, 3))),
            )
        )

        assertEquals(44_100, header.sampleRate)
        assertEquals(16L, header.dataSize)
    }

    @Test
    fun clampsDataSizeToFileLength() {
        val header = parse(
            wav(
                formatTag = WavHeaderParser.WAVE_FORMAT_PCM,
                channels = 2,
                sampleRate = 44_100,
                bits = 16,
                dataBytes = 16,
                dataDeclaredSize = 9_999,
            )
        )

        assertEquals(16L, header.dataSize)
    }

    @Test
    fun fallsBackToFileLengthWhenDataSizeIsMissing() {
        val header = parse(
            wav(
                formatTag = WavHeaderParser.WAVE_FORMAT_PCM,
                channels = 1,
                sampleRate = 8_000,
                bits = 8,
                dataBytes = 100,
                dataDeclaredSize = 0xFFFFFFFF.toInt(),
            )
        )

        assertEquals(100L, header.dataSize)
        assertEquals(100L, header.durationFrames)
        assertEquals(12L, header.durationMs)
    }

    @Test(expected = IOException::class)
    fun rejectsNonWaveContainer() {
        parse(
            wav(
                riffId = "RIFX",
                formatTag = WavHeaderParser.WAVE_FORMAT_PCM,
                dataBytes = 8,
            )
        )
    }

    @Test(expected = IOException::class)
    fun rejectsUnsupportedBitDepth() {
        parse(
            wav(
                formatTag = WavHeaderParser.WAVE_FORMAT_PCM,
                bits = 12,
                dataBytes = 8,
            )
        )
    }

    @Test(expected = IOException::class)
    fun rejectsUnsupportedEncoding() {
        parse(
            wav(
                formatTag = 0x0055, // MP3 inside WAV is not supported.
                dataBytes = 8,
            )
        )
    }

    @Test(expected = IOException::class)
    fun rejectsMissingFormatChunk() {
        parse(
            container(
                "RIFF",
                listOf(chunkWithSize("data", ByteArray(8), -1)),
            )
        )
    }

    @Test(expected = IOException::class)
    fun rejectsTruncatedHeader() {
        parse(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte()))
    }

    private fun parse(bytes: ByteArray): WavHeader =
        WavHeaderParser.parse(ByteArraySource(bytes))

    private fun wav(
        riffId: String = "RIFF",
        formatTag: Int,
        channels: Int = 2,
        sampleRate: Int = 44_100,
        bits: Int = 16,
        dataBytes: Int = 8,
        fmtChunkSize: Int = 16,
        subFormat: Int = formatTag,
        dataDeclaredSize: Int = -1,
        ds64DataSize: Long = -1L,
        extraChunks: List<ByteArray> = emptyList(),
    ): ByteArray {
        val fmt = chunk("fmt ", fmtBody(formatTag, channels, sampleRate, bits, fmtChunkSize, subFormat))
        val data = chunkWithSize("data", ByteArray(dataBytes), dataDeclaredSize)
        val ds64 = if (ds64DataSize >= 0) {
            chunk("ds64", ds64Body(dataBytes.toLong(), ds64DataSize))
        } else {
            null
        }
        val parts = buildList {
            ds64?.let(::add)
            addAll(extraChunks)
            add(fmt)
            add(data)
        }
        return container(riffId, parts)
    }

    private fun container(riffId: String, parts: List<ByteArray>): ByteArray {
        val body = parts.fold(ByteArray(0)) { acc, part -> acc + part }
        val out = ByteArray(12 + body.size)
        riffId.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        le32(out, 4, out.size - 8)
        "WAVE".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
        body.copyInto(out, 12)
        return out
    }

    private fun fmtBody(
        formatTag: Int,
        channels: Int,
        sampleRate: Int,
        bits: Int,
        size: Int,
        subFormat: Int,
    ): ByteArray {
        val body = ByteArray(size)
        val blockAlign = channels * bits / 8
        le16(body, 0, formatTag)
        le16(body, 2, channels)
        le32(body, 4, sampleRate)
        le32(body, 8, sampleRate * blockAlign)
        le16(body, 12, blockAlign)
        le16(body, 14, bits)
        if (size >= 18) le16(body, 16, 22)
        if (size >= 40) {
            le16(body, 18, bits)
            le16(body, 24, subFormat)
        }
        return body
    }

    private fun ds64Body(riffSize: Long, dataSize: Long): ByteArray {
        val body = ByteArray(28)
        le64(body, 0, riffSize)
        le64(body, 8, dataSize)
        le64(body, 16, 0)
        le32(body, 24, 0)
        return body
    }

    private fun chunk(id: String, body: ByteArray): ByteArray =
        chunkWithSize(id, body, -1)

    private fun chunkWithSize(id: String, body: ByteArray, declaredSize: Int): ByteArray {
        val padding = body.size and 1
        val out = ByteArray(8 + body.size + padding)
        id.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        le32(out, 4, if (declaredSize >= 0) declaredSize else body.size)
        body.copyInto(out, 8)
        return out
    }

    private fun le16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
    }

    private fun le32(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value shr 8).toByte()
        bytes[offset + 2] = (value shr 16).toByte()
        bytes[offset + 3] = (value shr 24).toByte()
    }

    private fun le64(bytes: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 8) {
            bytes[offset + i] = (value shr (8 * i)).toByte()
        }
    }
}
