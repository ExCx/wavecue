package com.wavecue.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Regression tests for byte-exact WAV sample decoding. The waveform display and
 * silence detection depend on the analyser receiving identical float samples from
 * every bit-depth representation of the same audio.
 */
class WavPcmSourceDecodeTest {

    /** Builds one frame of a known value in each representation. */
    private fun sampleBytes(value: Double, bits: Int, bytesPerSample: Int): ByteArray {
        val out = ByteArray(bytesPerSample)
        val max = (1L shl (bits - 1)) - 1
        val min = -(1L shl (bits - 1))
        val int = when {
            value >= 0 -> (value * max).toLong().coerceIn(0, max)
            else -> (value * -min).toLong().coerceIn(min, 0)
        }
        // little-endian, in the low `bits` bits of the container
        for (i in 0 until bytesPerSample) {
            out[i] = ((int shr (i * 8)) and 0xFF).toByte()
        }
        return out
    }

    private fun decode(bits: Int, bytesPerSample: Int, value: Double): Float {
        val bytes = sampleBytes(value, bits, bytesPerSample)
        val out = FloatArray(1)
        decodeWavSamples(
            bytes = bytes,
            frames = 1,
            channels = 1,
            bytesPerSample = bytesPerSample,
            validBits = bits,
            isFloat = false,
            framePadding = bytesPerSample - (1 * bytesPerSample),
            out = out,
        )
        return out[0]
    }

    @Test
    fun `16-bit and packed 24-bit decode to the same float`() {
        for (value in listOf(-1.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75, 1.0)) {
            val a16 = decode(bits = 16, bytesPerSample = 2, value = value)
            val a24 = decode(bits = 24, bytesPerSample = 3, value = value)
            assertTrue(
                "value=$value 16-bit=$a16 24-bit=$a24",
                abs(a16 - a24) < 1e-4,
            )
        }
    }

    @Test
    fun `packed 24-bit and 24-in-32 padded decode to the same float`() {
        // 24-in-32: valid bits live in the low 3 bytes of a 4-byte container.
        for (value in listOf(-1.0, -0.5, 0.0, 0.5, 1.0)) {
            val packed = decode(bits = 24, bytesPerSample = 3, value = value)
            val padded = decode(bits = 24, bytesPerSample = 4, value = value)
            assertTrue(
                "value=$value packed=$packed padded=$padded",
                abs(packed - padded) < 1e-4,
            )
        }
    }

    @Test
    fun `decode is symmetric and bounded`() {
        val min = decode(bits = 24, bytesPerSample = 3, value = -1.0)
        val max = decode(bits = 24, bytesPerSample = 3, value = 1.0)
        assertEquals(-1.0, min.toDouble(), 1e-4)
        assertEquals(1.0, max.toDouble(), 1e-4)
    }
}
