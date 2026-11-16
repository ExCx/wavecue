package com.wavecue.core.analysis

import com.wavecue.core.model.SilenceRegion
import kotlin.math.log10
import kotlin.math.max

/**
 * Silence detection over the analysed RMS envelope. Silence is defined with hysteresis:
 * a run must stay below [thresholdDb] for [minDurationMs], and short loud transients
 * (under [mergeGapMs]) do not split a run.
 */
object SilenceDetector {

    fun detect(
        waveform: WaveformData,
        thresholdDb: Float,
        minDurationMs: Long,
        includeEdges: Boolean = false,
        mergeGapMs: Long = 150,
    ): List<SilenceRegion> {
        if (waveform.binCount == 0 || waveform.sampleRate <= 0) return emptyList()
        val binMs = waveform.framesPerBin * 1000.0 / waveform.sampleRate
        if (binMs <= 0.0) return emptyList()

        val silent = BooleanArray(waveform.binCount) { index ->
            val rms = waveform.rms[index] / 32767.0
            val db = 20.0 * log10(max(rms, 1e-7))
            db <= thresholdDb
        }

        val runs = ArrayList<IntRange>()
        var start = -1
        for (i in silent.indices) {
            if (silent[i] && start < 0) start = i
            if (!silent[i] && start >= 0) {
                runs.add(start until i)
                start = -1
            }
        }
        if (start >= 0) runs.add(start until silent.size)
        if (runs.isEmpty()) return emptyList()

        val merged = ArrayList<IntRange>()
        var current = runs.first()
        for (i in 1 until runs.size) {
            val next = runs[i]
            val gapMs = (next.first - current.last) * binMs
            current = if (gapMs <= mergeGapMs) {
                current.first until (next.last + 1)
            } else {
                merged.add(current)
                next
            }
        }
        merged.add(current)

        val durationMs = waveform.durationMs
        val regions = ArrayList<SilenceRegion>(merged.size)
        for (range in merged) {
            val regionStart = (range.first * binMs).toLong()
            val regionEnd = ((range.last + 1) * binMs).toLong().coerceAtMost(durationMs)
            if (regionEnd - regionStart < minDurationMs) continue
            if (!includeEdges) {
                if (regionStart <= 50) continue
                if (durationMs - regionEnd <= 50) continue
            }
            regions.add(SilenceRegion(regionStart, regionEnd))
        }
        return regions
    }
}
