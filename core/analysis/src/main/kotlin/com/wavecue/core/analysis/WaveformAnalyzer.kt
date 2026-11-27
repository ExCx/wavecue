package com.wavecue.core.analysis

import android.content.Context
import android.net.Uri
import com.wavecue.core.audio.PcmSourceFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Single-pass waveform analysis. Reads PCM once, computes the base peak pyramid,
 * measures the exact frame count, and never touches the UI thread.
 */
class WaveformAnalyzer(private val context: Context) {

    suspend fun analyze(
        uri: Uri,
        displayName: String,
        framesPerBin: Int = 64,
        onProgress: (Float) -> Unit = {},
    ): WaveformData = withContext(Dispatchers.IO) {
        val source = PcmSourceFactory.open(context, uri, displayName)
        try {
            source.seekToFrame(0)
            android.util.Log.d("WaveformAnalyzer", "opened $displayName: sr=${source.sampleRate} ch=${source.channelCount} bd=${source.bitDepth} dur=${source.durationFrames} codec=${source.codec}")
            val channels = source.channelCount
            val sampleRate = source.sampleRate
            val bitDepth = source.bitDepth
            val expectedFrames = source.durationFrames
            val chunkFrames = 8192
            val buffer = FloatArray(chunkFrames * channels)

            val mins = ArrayList<Short>(1024)
            val maxs = ArrayList<Short>(1024)
            val rmsValues = ArrayList<Short>(1024)

            var binMin = Float.MAX_VALUE
            var binMax = -Float.MAX_VALUE
            var binSumSq = 0.0
            var binSamples = 0
            var totalFrames = 0L
            var lastProgress = 0L

            while (true) {
                coroutineContext.ensureActive()
                val framesRead = source.readFloat(buffer, chunkFrames)
                if (framesRead <= 0) break
                var sampleIndex = 0
                val sampleEnd = framesRead * channels
                while (sampleIndex < sampleEnd) {
                    val value = buffer[sampleIndex]
                    if (value < binMin) binMin = value
                    if (value > binMax) binMax = value
                    binSumSq += value.toDouble() * value
                    binSamples++
                    sampleIndex++
                    if (binSamples >= framesPerBin * channels) {
                        val rms = sqrt(binSumSq / binSamples).coerceIn(0.0, 1.0)
                        mins.add(toShort(binMin))
                        maxs.add(toShort(binMax))
                        rmsValues.add((rms * 32767.0).toInt().coerceIn(0, 32767).toShort())
                        binMin = Float.MAX_VALUE
                        binMax = -Float.MAX_VALUE
                        binSumSq = 0.0
                        binSamples = 0
                    }
                }
                totalFrames += framesRead
                if (expectedFrames > 0 && totalFrames - lastProgress >= expectedFrames / 100) {
                    lastProgress = totalFrames
                    onProgress((totalFrames.toDouble() / expectedFrames).toFloat().coerceIn(0f, 0.99f))
                }
            }

            if (binSamples > 0) {
                val rms = sqrt(binSumSq / binSamples).coerceIn(0.0, 1.0)
                mins.add(toShort(binMin))
                maxs.add(toShort(binMax))
                rmsValues.add((rms * 32767.0).toInt().coerceIn(0, 32767).toShort())
            }
            if (mins.isEmpty()) {
                mins.add(0)
                maxs.add(0)
                rmsValues.add(0)
            }
            onProgress(1f)

            val durationMs = if (sampleRate > 0) totalFrames * 1000 / sampleRate else 0L
            android.util.Log.d("WaveformAnalyzer", "analyzed $displayName: bins=${mins.size} sr=$sampleRate ch=$channels bd=$bitDepth firstMin=${mins.getOrElse(0){0}} firstMax=${maxs.getOrElse(0){0}} firstRms=${rmsValues.getOrElse(0){0}} durationMs=$durationMs totalFrames=$totalFrames firstBuf=${buffer.getOrElse(0){0f}},${buffer.getOrElse(1){0f}},${buffer.getOrElse(2){0f}}")
            WaveformData(
                sampleRate = sampleRate,
                channelCount = channels,
                framesPerBin = framesPerBin,
                durationMs = durationMs,
                min = mins.toShortArray(),
                max = maxs.toShortArray(),
                rms = rmsValues.toShortArray(),
            )
        } finally {
            source.close()
        }
    }

    private fun toShort(value: Float): Short {
        val scaled = (value.coerceIn(-1f, 1f) * 32767f).toInt()
        return scaled.coerceIn(-32768, 32767).toShort()
    }

    companion object {
        fun peakToDb(rmsShort: Short): Double {
            val normalized = max(1e-7, rmsShort / 32767.0)
            return 20.0 * kotlin.math.log10(normalized)
        }

        fun dbToAmplitude(db: Double): Double = 10.0.pow(db / 20.0)

        fun amplitudeToShort(amplitude: Double): Short {
            val clamped = min(1.0, max(0.0, amplitude))
            return (clamped * 32767.0).toInt().toShort()
        }
    }
}
