package com.wavecue.app.ui.library

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wavecue.app.playback.PlaybackController
import com.wavecue.core.data.repo.LibraryRepository
import com.wavecue.core.data.repo.LibraryRoot
import com.wavecue.core.data.repo.PlaylistRepository
import com.wavecue.core.data.saf.DocumentNode
import com.wavecue.core.model.NaturalOrder
import com.wavecue.core.model.PlaylistSummary
import com.wavecue.core.model.QueueItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class FolderCrumb(val documentId: String, val name: String)

data class LibraryUiState(
    val root: LibraryRoot? = null,
    val path: List<FolderCrumb> = emptyList(),
    val folders: List<DocumentNode> = emptyList(),
    val tracks: List<DocumentNode> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val playingUri: String? = null,
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val controller: PlaybackController,
) : ViewModel() {

    val roots: StateFlow<List<LibraryRoot>> = library.observeRoots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val playlistOptions: StateFlow<List<PlaylistSummary>> = playlists.observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    private var loadJob: kotlinx.coroutines.Job? = null

    fun addRoot(treeUri: Uri) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val id = library.addRoot(treeUri)
                val root = library.rootById(id)
                if (root != null) openRoot(root)
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(loading = false, error = t.message ?: "Cannot open that folder")
            }
        }
    }

    fun removeRoot(id: Long) {
        viewModelScope.launch {
            library.removeRoot(id)
            if (_ui.value.root?.id == id) closeRoot()
        }
    }

    fun openRoot(root: LibraryRoot) {
        _ui.value = LibraryUiState(root = root, loading = true)
        load(root, null)
    }

    fun openFolder(node: DocumentNode) {
        val state = _ui.value
        val root = state.root ?: return
        _ui.value = state.copy(
            path = state.path + FolderCrumb(node.documentId, node.name),
            folders = emptyList(),
            tracks = emptyList(),
            loading = true,
            error = null,
        )
        load(root, node.documentId)
    }

    fun navigateUp() {
        val state = _ui.value
        val root = state.root ?: return
        if (state.path.isEmpty()) {
            closeRoot()
            return
        }
        val path = state.path.dropLast(1)
        _ui.value = state.copy(
            path = path,
            folders = emptyList(),
            tracks = emptyList(),
            loading = true,
            error = null,
        )
        load(root, path.lastOrNull()?.documentId)
    }

    fun closeRoot() {
        loadJob?.cancel()
        _ui.value = LibraryUiState()
    }

    fun refresh() {
        val state = _ui.value
        val root = state.root ?: return
        load(root, state.path.lastOrNull()?.documentId)
    }

    private fun load(root: LibraryRoot, parentDocumentId: String?) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val state = _ui.value
            _ui.value = state.copy(loading = true, error = null)
            try {
                val nodes = library.listChildren(Uri.parse(root.treeUri), parentDocumentId)
                val folders = nodes.filter { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name })
                val tracks = nodes.filterNot { it.isDirectory }.sortedWith(compareBy(NaturalOrder) { it.name })
                _ui.value = _ui.value.copy(
                    folders = folders,
                    tracks = tracks,
                    loading = false,
                    error = null,
                )
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(
                    loading = false,
                    error = t.message ?: "This folder could not be read",
                )
            }
        }
    }

    fun playFolder(node: DocumentNode) {
        val state = _ui.value
        val root = state.root ?: return
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            try {
                val indexed = withContext(Dispatchers.IO) {
                    state.tracks.map { candidate ->
                        candidate to library.ensureTrack(candidate)
                    }
                }
                val items = indexed.map { (doc, track) ->
                    QueueItem(trackId = track.id, uri = doc.uri.toString(), name = doc.name)
                }
                val startIndex = items.indexOfFirst { it.uri == node.uri.toString() }.coerceAtLeast(0)
                if (items.isEmpty()) {
                    _ui.value = _ui.value.copy(loading = false, error = "This folder has no playable audio")
                    return@launch
                }
                val label = state.path.lastOrNull()?.name ?: root.name
                withContext(Dispatchers.Main) {
                    controller.playQueue(items, startIndex, "Folder: $label")
                }
                _ui.value = _ui.value.copy(loading = false, playingUri = node.uri.toString())
            } catch (t: Throwable) {
                _ui.value = _ui.value.copy(loading = false, error = t.message ?: "Cannot start playback")
            }
        }
    }

    fun addToPlaylist(playlistId: Long, node: DocumentNode) {
        viewModelScope.launch {
            try {
                val track = library.ensureTrack(node)
                playlists.addTrack(playlistId, track.id)
            } catch (_: Throwable) {
                // A failing insert is visible through the playlist staying unchanged.
            }
        }
    }

    fun clearError() {
        _ui.value = _ui.value.copy(error = null)
    }
}
