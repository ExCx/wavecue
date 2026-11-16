package com.wavecue.core.data.repo

import android.content.Context
import android.net.Uri
import com.wavecue.core.analysis.WaveformAnalyzer
import com.wavecue.core.analysis.WaveformData
import com.wavecue.core.analysis.WaveformStore
import com.wavecue.core.data.db.TrackDao
import com.wavecue.core.data.db.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AnalysisRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val trackDao: TrackDao,
) {
    private val analyzer = WaveformAnalyzer(context)
    private val store = WaveformStore(context)
    private val locks = ConcurrentHashMap<Long, Mutex>()
    private val cache = object : LinkedHashMap<Long, WaveformData>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, WaveformData>?): Boolean =
            size > 3
    }
    private val _progress = MutableStateFlow<Map<Long, Float>>(emptyMap())
    val progress: StateFlow<Map<Long, Float>> = _progress
    private val _failures = MutableStateFlow<Map<Long, String>>(emptyMap())
    val failures: StateFlow<Map<Long, String>> = _failures

    fun cached(trackId: Long): WaveformData? = synchronized(cache) { cache[trackId] }

    suspend fun getOrAnalyze(track: TrackEntity): WaveformData? {
        cached(track.id)?.let { return it }
        val mutex = locks.getOrPut(track.id) { Mutex() }
        return mutex.withLock {
            cached(track.id)?.let { return@withLock it }

            val file = store.fileFor(track.id)
            if (file.exists()) {
                val fromDisk = WaveformData.read(file)
                if (fromDisk != null) {
                    synchronized(cache) { cache[track.id] = fromDisk }
                    trackDao.updateAnalysis(
                        id = track.id,
                        durationMs = fromDisk.durationMs,
                        sampleRate = fromDisk.sampleRate,
                        channels = fromDisk.channelCount,
                        bitDepth = track.bitDepth,
                        codec = track.codec,
                        waveformPath = file.absolutePath,
                        state = STATE_READY,
                    )
                    return@withLock fromDisk
                }
                file.delete()
            }

            trackDao.updateAnalysisState(track.id, STATE_ANALYZING)
            _progress.value = _progress.value + (track.id to 0f)
            try {
                val data = withContext(Dispatchers.IO) {
                    analyzer.analyze(
                        uri = Uri.parse(track.uri),
                        displayName = track.name,
                        onProgress = { value ->
                            _progress.value = _progress.value + (track.id to value)
                        },
                    )
                }
                WaveformData.write(file, data)
                trackDao.updateAnalysis(
                    id = track.id,
                    durationMs = data.durationMs,
                    sampleRate = data.sampleRate,
                    channels = data.channelCount,
                    bitDepth = track.bitDepth,
                    codec = track.codec,
                    waveformPath = file.absolutePath,
                    state = STATE_READY,
                )
                synchronized(cache) { cache[track.id] = data }
                _failures.value = _failures.value - track.id
                data
            } catch (t: Throwable) {
                trackDao.updateAnalysisState(track.id, STATE_FAILED)
                _failures.value = _failures.value + (track.id to (t.message ?: "Analysis failed"))
                null
            } finally {
                _progress.value = _progress.value - track.id
            }
        }
    }

    suspend fun clearCache() = withContext(Dispatchers.IO) {
        synchronized(cache) { cache.clear() }
        store.clear()
        trackDao.resetAllAnalysis()
    }

    fun invalidate(trackId: Long) {
        synchronized(cache) { cache.remove(trackId) }
        store.delete(trackId)
    }

    companion object {
        const val STATE_NONE = 0
        const val STATE_ANALYZING = 1
        const val STATE_READY = 2
        const val STATE_FAILED = 3
    }
}
