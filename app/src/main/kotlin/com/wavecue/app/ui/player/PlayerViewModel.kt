package com.wavecue.app.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavecue.app.export.ExportManager
import com.wavecue.app.playback.PlaybackController
import com.wavecue.core.analysis.SilenceDetector
import com.wavecue.core.analysis.WaveformData
import com.wavecue.core.data.db.TrackEntity
import com.wavecue.core.data.repo.AnalysisRepository
import com.wavecue.core.data.repo.AnnotationRepository
import com.wavecue.core.data.repo.LibraryRepository
import com.wavecue.core.data.repo.SettingsRepository
import com.wavecue.core.model.AppSettings
import com.wavecue.core.model.FlagItem
import com.wavecue.core.model.NoteItem
import com.wavecue.core.model.SilenceRegion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val controller: PlaybackController,
    private val library: LibraryRepository,
    private val annotations: AnnotationRepository,
    private val analysis: AnalysisRepository,
    settings: SettingsRepository,
    private val exportManager: ExportManager,
) : ViewModel() {

    val state = controller.state
    val positionMs = controller.positionMs
    val media = controller.media
    val current = controller.current
    val queue = controller.queue
    val queueLabel = controller.queueLabel
    val diagnostics = controller.diagnostics

    private val _track = MutableStateFlow<TrackEntity?>(null)
    val track: StateFlow<TrackEntity?> = _track

    private val _waveform = MutableStateFlow<WaveformData?>(null)
    val waveform: StateFlow<WaveformData?> = _waveform

    private val _progress = MutableStateFlow<Float?>(null)
    val progress: StateFlow<Float?> = _progress

    private val _pendingSilence = MutableStateFlow<List<SilenceRegion>>(emptyList())
    val pendingSilence: StateFlow<List<SilenceRegion>> = _pendingSilence

    private val _detecting = MutableStateFlow(false)
    val detecting: StateFlow<Boolean> = _detecting

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val settings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val flags: StateFlow<List<FlagItem>> = current
        .flatMapLatest { item ->
            if (item == null) flowOf(emptyList()) else annotations.flags(item.trackId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val notes: StateFlow<List<NoteItem>> = current
        .flatMapLatest { item ->
            if (item == null) flowOf(emptyList()) else annotations.notes(item.trackId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            current.map { it?.trackId }.distinctUntilChanged().collectLatest { trackId ->
                _track.value = null
                _waveform.value = null
                _pendingSilence.value = emptyList()
                _progress.value = null
                if (trackId == null) return@collectLatest
                val entity = library.track(trackId) ?: return@collectLatest
                _track.value = entity
                val cached = analysis.cached(trackId)
                if (cached != null) {
                    _waveform.value = cached
                } else {
                    _waveform.value = analysis.getOrAnalyze(entity)
                    _track.value = library.track(trackId) ?: entity
                }
            }
        }
        viewModelScope.launch {
            analysis.progress.collect { progressMap ->
                val trackId = current.value?.trackId ?: return@collect
                _progress.value = progressMap[trackId]
            }
        }
        viewModelScope.launch {
            analysis.failures.collect { failures ->
                val trackId = current.value?.trackId ?: return@collect
                if (failures.containsKey(trackId)) _progress.value = null
            }
        }
    }

    fun toggle() = controller.toggle()
    fun play() = controller.play()
    fun pause() = controller.pause()
    fun next() = controller.next()
    fun previous() = controller.previous()
    fun seekTo(ms: Long) = controller.seekTo(ms)
    fun seekBy(deltaMs: Long) = controller.seekBy(deltaMs)

    fun addFlagAtPlayhead(title: String) {
        val trackId = current.value?.trackId ?: return
        val time = positionMs.value
        viewModelScope.launch { annotations.addFlag(trackId, time, title) }
    }

    fun addFlagAt(timeMs: Long, title: String) {
        val trackId = current.value?.trackId ?: return
        viewModelScope.launch { annotations.addFlag(trackId, timeMs, title) }
    }

    fun updateFlag(id: Long, timeMs: Long, title: String) {
        viewModelScope.launch { annotations.updateFlag(id, timeMs, title) }
    }

    fun deleteFlag(id: Long) {
        viewModelScope.launch { annotations.deleteFlag(id) }
    }

    fun addNoteAtPlayhead(body: String) {
        val trackId = current.value?.trackId ?: return
        val time = positionMs.value
        viewModelScope.launch { annotations.addNote(trackId, time, body) }
    }

    fun addNoteAt(timeMs: Long, body: String) {
        val trackId = current.value?.trackId ?: return
        viewModelScope.launch { annotations.addNote(trackId, timeMs, body) }
    }

    fun updateNote(id: Long, timeMs: Long, body: String) {
        viewModelScope.launch { annotations.updateNote(id, timeMs, body) }
    }

    fun deleteNote(id: Long) {
        viewModelScope.launch { annotations.deleteNote(id) }
    }

    fun detectSilence() {
        val waveform = _waveform.value ?: return
        if (_detecting.value) return
        _pendingSilence.value = emptyList()
        viewModelScope.launch {
            _detecting.value = true
            val values = settings.value
            _pendingSilence.value = SilenceDetector.detect(
                waveform = waveform,
                thresholdDb = values.silenceThresholdDb,
                minDurationMs = values.silenceMinDurationMs,
                includeEdges = values.includeEdgeSilence,
            )
            _detecting.value = false
        }
    }

    fun dismissSilence() {
        _pendingSilence.value = emptyList()
    }

    fun acceptSilence(regions: List<SilenceRegion>) {
        val trackId = current.value?.trackId ?: return
        val startIndex = flags.value.size + 1
        _pendingSilence.value = emptyList()
        viewModelScope.launch { annotations.addSilenceFlags(trackId, regions, startIndex) }
    }

    fun retryAnalysis() {
        val entity = _track.value ?: return
        analysis.invalidate(entity.id)
        _waveform.value = null
        viewModelScope.launch {
            _waveform.value = analysis.getOrAnalyze(entity)
            _track.value = library.track(entity.id) ?: entity
        }
    }

    fun exportNotes(uri: android.net.Uri) {
        val trackId = current.value?.trackId ?: return
        viewModelScope.launch {
            try {
                val ok = exportManager.exportTrackMarkdown(uri, trackId)
                _message.value = if (ok) "Notes exported" else "Nothing to export"
            } catch (t: Throwable) {
                _message.value = t.message ?: "Export failed"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
