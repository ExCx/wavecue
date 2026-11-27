package com.wavecue.core.analysis

import java.io.File

/**
 * Multi-resolution peak pyramid. The base level stores min/max/RMS for every
 * [framesPerBin] frames; each following level merges four bins of the previous one.
 * Rendering picks the level where one bin covers a fraction of a pixel.
 */
class WaveformData(
    val sampleRate: Int,
    val channelCount: Int,
    val framesPerBin: Int,
    val durationMs: Long,
    val min: ShortArray,
    val max: ShortArray,
    val rms: ShortArray,
) {
    val binCount: Int get() = min.size
    val levels: List<Level> = buildLevels()

    val binDurationMs: Double =
        if (sampleRate > 0) framesPerBin * 1000.0 / sampleRate else 0.0

    class Level(
        val framesPerBin: Long,
        val min: ShortArray,
        val max: ShortArray,
        val rms: ShortArray,
    ) {
        val binCount: Int get() = min.size
    }

    fun levelFor(framesPerPixel: Double): Level {
        var result = levels.first()
        for (i in 1 until levels.size) {
            val candidate = levels[i]
            if (candidate.framesPerBin <= framesPerPixel) result = candidate else break
        }
        return result
    }

    fun durationFrames(): Long = binCount.toLong() * framesPerBin

    fun frameToMs(frame: Long): Long =
        if (sampleRate > 0) frame * 1000 / sampleRate else 0L

    fun msToFrame(ms: Long): Long =
        if (sampleRate > 0) ms * sampleRate / 1000 else 0L

    private fun buildLevels(): List<Level> {
        val result = ArrayList<Level>()
        result.add(Level(framesPerBin.toLong(), min, max, rms))
        var previous = result.last()
        while (previous.binCount > 2) {
            val nextCount = (previous.binCount + 3) / 4
            val nextMin = ShortArray(nextCount)
            val nextMax = ShortArray(nextCount)
            val nextRms = ShortArray(nextCount)
            for (i in 0 until nextCount) {
                val from = i * 4
                val to = minOf(from + 4, previous.binCount)
                var lo = Short.MAX_VALUE
                var hi = Short.MIN_VALUE
                var sumSq = 0.0
                var count = 0
                for (j in from until to) {
                    if (previous.min[j] < lo) lo = previous.min[j]
                    if (previous.max[j] > hi) hi = previous.max[j]
                    val normalized = previous.rms[j] / 32767.0
                    sumSq += normalized * normalized
                    count++
                }
                nextMin[i] = lo
                nextMax[i] = hi
                nextRms[i] = if (count > 0) {
                    (kotlin.math.sqrt(sumSq / count) * 32767.0).toInt().coerceIn(0, 32767).toShort()
                } else {
                    0
                }
            }
            val level = Level(previous.framesPerBin * 4, nextMin, nextMax, nextRms)
            result.add(level)
            previous = level
        }
        return result
    }

    companion object {
        fun read(file: File): WaveformData? {
            if (!file.isFile) return null
            return try {
                file.inputStream().buffered().use { stream ->
                    val magic = ByteArray(4)
                    if (stream.read(magic) != 4) return null
                    if (String(magic, Charsets.US_ASCII) != "WCV2") return null
                    val header = ByteArray(4 * 4 + 8)
                    if (stream.read(header) != header.size) return null
                    val buffer = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    val version = buffer.int
                    if (version != 2) return null
                    val sampleRate = buffer.int
                    val channels = buffer.int
                    val framesPerBin = buffer.int
                    val durationMs = buffer.long
                    val binCount = stream.readIntLe()
                    if (binCount <= 0 || binCount > 200_000_000) return null
                    val min = ShortArray(binCount)
                    val max = ShortArray(binCount)
                    val rms = ShortArray(binCount)
                    val chunk = ByteArray(binCount * 6)
                    var offset = 0
                    while (offset < chunk.size) {
                        val read = stream.read(chunk, offset, chunk.size - offset)
                        if (read < 0) break
                        offset += read
                    }
                    if (offset < chunk.size) return null
                    val data = java.nio.ByteBuffer.wrap(chunk).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (i in 0 until binCount) {
                        min[i] = data.short
                        max[i] = data.short
                        rms[i] = data.short
                    }
                    WaveformData(sampleRate, channels, framesPerBin, durationMs, min, max, rms)
                }
            } catch (_: Exception) {
                null
            }
        }

        fun write(file: File, data: WaveformData) {
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.outputStream().buffered().use { stream ->
                stream.write("WCV2".toByteArray(Charsets.US_ASCII))
                val header = java.nio.ByteBuffer.allocate(4 * 4 + 8).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                header.putInt(2)
                header.putInt(data.sampleRate)
                header.putInt(data.channelCount)
                header.putInt(data.framesPerBin)
                header.putLong(data.durationMs)
                stream.write(header.array())
                stream.writeIntLe(data.binCount)
                val chunk = java.nio.ByteBuffer.allocate(data.binCount * 6).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until data.binCount) {
                    chunk.putShort(data.min[i])
                    chunk.putShort(data.max[i])
                    chunk.putShort(data.rms[i])
                }
                stream.write(chunk.array())
            }
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }

        private fun java.io.InputStream.readIntLe(): Int {
            val bytes = ByteArray(4)
            if (read(bytes) != 4) return -1
            return (bytes[0].toInt() and 0xFF) or
                ((bytes[1].toInt() and 0xFF) shl 8) or
                ((bytes[2].toInt() and 0xFF) shl 16) or
                ((bytes[3].toInt() and 0xFF) shl 24)
        }

        private fun java.io.OutputStream.writeIntLe(value: Int) {
            write(value and 0xFF)
            write((value shr 8) and 0xFF)
            write((value shr 16) and 0xFF)
            write((value shr 24) and 0xFF)
        }
    }
}
