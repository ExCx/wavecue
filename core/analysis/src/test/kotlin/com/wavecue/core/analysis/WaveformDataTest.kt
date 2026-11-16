package com.wavecue.core.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WaveformDataTest {

    /** Builds [binCount] bins whose values are supplied by [minAt], [maxAt], [rmsAt]. */
    private fun waveform(
        binCount: Int,
        framesPerBin: Int = 64,
        sampleRate: Int = 64_000,
        minAt: (Int) -> Short = { 0 },
        maxAt: (Int) -> Short = { 0 },
        rmsAt: (Int) -> Short = { 0 },
    ): WaveformData {
        val durationMs = binCount.toLong() * framesPerBin * 1000 / sampleRate
        return WaveformData(
            sampleRate = sampleRate,
            channelCount = 2,
            framesPerBin = framesPerBin,
            durationMs = durationMs,
            min = ShortArray(binCount, minAt),
            max = ShortArray(binCount, maxAt),
            rms = ShortArray(binCount, rmsAt),
        )
    }

    @Test
    fun firstLevelIsTheBaseBinResolution() {
        val data = waveform(binCount = 10)
        assertEquals(64L, data.levels.first().framesPerBin)
        assertEquals(10, data.levels.first().binCount)
    }

    @Test
    fun eachLevelMergesFourBinsAndKeepsPeaks() {
        val data = waveform(
            binCount = 8,
            minAt = { index -> (-1000 - index).toShort() },
            maxAt = { index -> (1000 + index).toShort() },
            rmsAt = { 1000 },
        )

        assertEquals(2, data.levels.size)
        val merged = data.levels[1]
        assertEquals(256L, merged.framesPerBin)
        assertEquals(2, merged.binCount)
        // First merged bin covers indices 0..3, the second covers 4..7.
        assertEquals((-1003).toShort(), merged.min[0])
        assertEquals(1003.toShort(), merged.max[0])
        assertEquals((-1007).toShort(), merged.min[1])
        assertEquals(1007.toShort(), merged.max[1])
        // Equal RMS values merge to nearly the same value (sqrt of a squared
        // normalized value can land one unit below due to rounding).
        assertEquals(1000.0, merged.rms[0].toDouble(), 1.0)
        assertEquals(1000.0, merged.rms[1].toDouble(), 1.0)
    }

    @Test
    fun stopsBuildingLevelsWhenATwoBinLevelIsReached() {
        val data = waveform(binCount = 3)
        // Base level has 3 bins, so exactly one coarser level is built.
        assertEquals(2, data.levels.size)
        assertEquals(1, data.levels[1].binCount)
    }

    @Test
    fun levelForPicksTheCoarsestLevelThatStillFitsAPixel() {
        val data = waveform(binCount = 1000)
        assertSame(data.levels.first(), data.levelFor(1.0))
        assertSame(data.levels.first(), data.levelFor(64.0))
        assertSame(data.levels.first(), data.levelFor(255.9))
        assertSame(data.levels[1], data.levelFor(256.0))
        assertSame(data.levels[2], data.levelFor(1024.0))
        assertSame(data.levels[3], data.levelFor(4096.0))
        assertTrue(data.levels.last().framesPerBin > 64L)
    }

    @Test
    fun levelForNeverReturnsBeyondTheLastLevel() {
        val data = waveform(binCount = 1000)
        assertSame(data.levels.last(), data.levelFor(1e12))
    }

    @Test
    fun durationFramesAccountsForEveryBin() {
        val data = waveform(binCount = 25, framesPerBin = 64)
        assertEquals(1600L, data.durationFrames())
    }

    @Test
    fun frameAndMillisecondConversionIsInverseAtWholeMilliseconds() {
        val data = waveform(binCount = 4, sampleRate = 1000, framesPerBin = 100)
        assertEquals(1_500L, data.msToFrame(1_500))
        assertEquals(1_500L, data.frameToMs(1_500))
    }

    @Test
    fun binDurationMatchesSampleRate() {
        val data = waveform(binCount = 4, sampleRate = 48_000, framesPerBin = 64)
        assertEquals(64.0 * 1000.0 / 48_000.0, data.binDurationMs, 1e-9)
    }

    @Test
    fun emptyWaveformHasNoCoarserLevels() {
        val data = waveform(binCount = 0)
        assertEquals(1, data.levels.size)
        assertEquals(0, data.levels.first().binCount)
    }
}
