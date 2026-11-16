package com.wavecue.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavecue.app.export.ExportManager
import com.wavecue.core.data.repo.AnalysisRepository
import com.wavecue.core.data.repo.SettingsRepository
import com.wavecue.core.model.AppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val analysis: AnalysisRepository,
    private val exportManager: ExportManager,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun setBufferMs(value: Int) {
        viewModelScope.launch { repository.setBufferMs(value) }
    }

    fun setSilenceThreshold(value: Float) {
        viewModelScope.launch { repository.setSilenceThreshold(value) }
    }

    fun setSilenceMinDuration(value: Long) {
        viewModelScope.launch { repository.setSilenceMinDuration(value) }
    }

    fun setIncludeEdgeSilence(value: Boolean) {
        viewModelScope.launch { repository.setIncludeEdgeSilence(value) }
    }

    fun setExclusiveMode(value: Boolean) {
        viewModelScope.launch { repository.setExclusiveMode(value) }
    }

    fun setBitPerfectUsb(value: Boolean) {
        viewModelScope.launch { repository.setBitPerfectUsb(value) }
    }

    fun setFollowPlayhead(value: Boolean) {
        viewModelScope.launch { repository.setFollowPlayhead(value) }
    }

    fun clearCache() {
        viewModelScope.launch {
            try {
                analysis.clearCache()
                _message.value = "Waveform cache cleared. Tracks will be analysed again when opened."
            } catch (t: Throwable) {
                _message.value = t.message ?: "Could not clear the cache"
            }
        }
    }

    fun exportJson(uri: Uri) {
        viewModelScope.launch {
            try {
                val count = exportManager.exportJsonTo(uri)
                _message.value = "Exported $count annotations"
            } catch (t: Throwable) {
                _message.value = t.message ?: "Export failed"
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
