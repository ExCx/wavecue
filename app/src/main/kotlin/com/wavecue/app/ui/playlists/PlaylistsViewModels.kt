package com.wavecue.app.ui.playlists

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavecue.app.playback.PlaybackController
import com.wavecue.core.data.repo.PlaylistRepository
import com.wavecue.core.model.PlaylistDetail
import com.wavecue.core.model.PlaylistSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val repository: PlaylistRepository,
) : ViewModel() {

    val summaries: StateFlow<List<PlaylistSummary>> = repository.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun create(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.create(name) }
    }

    fun rename(id: Long, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.rename(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repository: PlaylistRepository,
    private val controller: PlaybackController,
) : ViewModel() {

    private val playlistId: Long = savedState.get<Long>("playlistId") ?: -1L

    val detail: StateFlow<PlaylistDetail?> = repository.observeDetail(playlistId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun play(startIndex: Int) {
        viewModelScope.launch {
            val current = detail.value ?: return@launch
            val items = repository.playbackItems(playlistId)
            if (items.isEmpty()) return@launch
            controller.playPlaylist(items, startIndex.coerceIn(0, items.lastIndex), current.name)
        }
    }

    fun shuffle() {
        viewModelScope.launch { repository.shuffle(playlistId) }
    }

    fun playAll() {
        play(0)
    }

    fun alphabetical() {
        viewModelScope.launch { repository.restoreAlphabetical(playlistId) }
    }

    fun remove(entryId: Long) {
        viewModelScope.launch { repository.removeEntry(entryId) }
    }

    fun move(entryId: Long, direction: Int) {
        viewModelScope.launch { repository.moveEntry(playlistId, entryId, direction) }
    }

    fun rename(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.rename(playlistId, name) }
    }

    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            repository.delete(playlistId)
            onDeleted()
        }
    }
}
