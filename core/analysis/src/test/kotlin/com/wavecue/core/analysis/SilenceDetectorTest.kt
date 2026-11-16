package com.wavecue.core.analysis

import com.wavecue.core.model.SilenceRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilenceDetectorTest {

    /**
     * Builds a waveform where each bin is either silent (RMS 0) or loud (near full scale).
     * With sampleRate 1000 and framesPerBin 100 every bin spans exactly 100 ms.
     */
    private fun waveform(
        silentBins: IntRange,
        binCount: Int = 100,
        sampleRate: Int = 1000,
        framesPerBin: Int = 100,
    ): WaveformData {
        val min = ShortArray(binCount)
        val max = ShortArray(binCount)
        val rms = ShortArray(binCount) { index ->
            if (index in silentBins) 0 else 30_000
        }
        val durationMs = binCount.toLong() * framesPerBin * 1000 / sampleRate
        return WaveformData(sampleRate, 2, framesPerBin, durationMs, min, max, rms)
    }

    @Test
    fun detectsInteriorSilence() {
        val data = waveform(20..49)
        val regions = SilenceDetector.detect(data, thresholdDb = -40f, minDurationMs = 2_000)
        assertEquals(listOf(SilenceRegion(2_000, 5_000)), regions)
    }

    @Test
    fun ignoresRegionsShorterThanMinimum() {
        val data = waveform(20..34) // 1.5 s of silence
        val regions = SilenceDetector.detect(data, thresholdDb = -40f, minDurationMs = 2_000)
        assertTrue(regions.isEmpty())
    }

    @Test
    fun ignoresLeadInAndTailByDefault() {
        assertTrue(
            SilenceDetector.detect(waveform(0..19), -40f, 2_000).isEmpty(),
        )
        assertTrue(
            SilenceDetector.detect(waveform(80..99), -40f, 2_000).isEmpty(),
        )
    }

    @Test
    fun includesEdgesWhenRequested() {
        val lead = SilenceDetector.detect(waveform(0..19), -40f, 2_000, includeEdges = true)
        assertEquals(listOf(SilenceRegion(0, 2_000)), lead)

        val tail = SilenceDetector.detect(waveform(80..99), -40f, 2_000, includeEdges = true)
        assertEquals(listOf(SilenceRegion(8_000, 10_000)), tail)
    }

    @Test
    fun mergesRunsSeparatedByShortTransient() {
        // 50 ms bins: two silent runs split by a single 50 ms loud bin, gap 100 ms.
        val rms = ShortArray(100) { index ->
            when (index) {
                in 40..59 -> 0
                in 61..80 -> 0
                else -> 30_000
            }
        }
        val data = WaveformData(
            sampleRate = 1000,
            channelCount = 2,
            framesPerBin = 50,
            durationMs = 5_000,
            min = ShortArray(100),
            max = ShortArray(100),
            rms = rms,
        )

        val regions = SilenceDetector.detect(data, -40f, 500, mergeGapMs = 150)
        assertEquals(listOf(SilenceRegion(2_000, 4_050)), regions)
    }

    @Test
    fun keepsRunsSeparatedByLongerTransient() {
        val rms = ShortArray(100) { index ->
            when (index) {
                in 40..59 -> 0
                in 61..80 -> 0
                else -> 30_000
            }
        }
        val data = WaveformData(
            sampleRate = 1000,
            channelCount = 2,
            framesPerBin = 50,
            durationMs = 5_000,
            min = ShortArray(100),
            max = ShortArray(100),
            rms = rms,
        )
        val regions = SilenceDetector.detect(data, -40f, 500, mergeGapMs = 50)
        assertEquals(
            listOf(SilenceRegion(2_000, 3_000), SilenceRegion(3_050, 4_050)),
            regions,
        )
    }

    @Test
    fun returnsEmptyForEmptyWaveform() {
        val data = WaveformData(1000, 2, 100, 0, ShortArray(0), ShortArray(0), ShortArray(0))
        assertTrue(SilenceDetector.detect(data, -40f, 1_000).isEmpty())
    }
}
