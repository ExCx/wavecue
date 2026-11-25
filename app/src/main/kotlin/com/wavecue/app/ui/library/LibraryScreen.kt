package com.wavecue.app.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wavecue.app.ui.TestTags
import com.wavecue.app.ui.components.EmptyState
import com.wavecue.app.ui.components.MetaChip
import com.wavecue.app.ui.theme.WaveCueColors
import com.wavecue.core.data.repo.LibraryRoot
import com.wavecue.core.data.saf.DocumentNode
import com.wavecue.core.model.PlaylistSummary

@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val playlists by viewModel.playlistOptions.collectAsStateWithLifecycle()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(viewModel::addRoot) }

    var addTarget by remember { mutableStateOf<DocumentNode?>(null) }
    var rootMenu by remember { mutableStateOf<Long?>(null) }

    Column(Modifier.fillMaxSize()) {
        if (ui.root == null) {
            RootsHeader(
                rootCount = roots.size,
                onAdd = { picker.launch(null) },
            )
        } else {
            BrowserHeader(
                title = ui.path.lastOrNull()?.name ?: ui.root?.name.orEmpty(),
                subtitle = ui.root?.name.orEmpty(),
                folderCount = ui.folders.size,
                trackCount = ui.tracks.size,
                onUp = viewModel::navigateUp,
                onRefresh = viewModel::refresh,
                onRemoveRoot = { rootMenu = ui.root?.id },
            )
        }

        if (ui.loading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = WaveCueColors.Primary,
                trackColor = WaveCueColors.Outline,
            )
        }

        ui.error?.let { error ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WaveCueColors.Error,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
            }
        }

        if (ui.root == null) {
            if (roots.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.LibraryMusic,
                    title = "Add your music folders",
                    message = "WaveCue reads WAV and MP3 from folders you pick. Access is granted per folder through Android's storage picker.",
                    actionLabel = "Choose a folder",
                    onAction = { picker.launch(null) },
                )
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(roots, key = { it.id }) { root ->
                        RootRow(
                            root = root,
                            onOpen = { viewModel.openRoot(root) },
                            onRemove = { viewModel.removeRoot(root.id) },
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 12.dp,
                    end = 12.dp,
                    bottom = 96.dp,
                ),
            ) {
                if (ui.tracks.isNotEmpty()) {
                    item {
                        Text(
                            "Playing a track here builds a temporary queue from this folder.",
                            style = MaterialTheme.typography.labelSmall,
                            color = WaveCueColors.TextSecondary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                        )
                    }
                }
                items(ui.folders, key = { it.documentId }) { folder ->
                    NodeRow(
                        icon = Icons.Filled.Folder,
                        name = folder.name,
                        subtitle = "Folder",
                        onClick = { viewModel.openFolder(folder) },
                    )
                }
                items(ui.tracks, key = { it.documentId }) { track ->
                    TrackRow(
                        node = track,
                        playing = ui.playingUri == track.uri.toString(),
                        onPlay = { viewModel.playFolder(track) },
                        onAddToPlaylist = { addTarget = track },
                    )
                }
                if (ui.folders.isEmpty() && ui.tracks.isEmpty() && !ui.loading) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.FolderOpen,
                            title = "Nothing playable here",
                            message = "This folder has no WAV or MP3 files and no subfolders.",
                        )
                    }
                }
            }
        }
    }

    rootMenu?.let { rootId ->
        val root = roots.firstOrNull { it.id == rootId }
        if (root != null) {
            AlertDialog(
                onDismissRequest = { rootMenu = null },
                title = { Text("Remove folder?") },
                text = {
                    Text(
                        "${root.name} will be removed from the library. Files and existing notes are untouched."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.removeRoot(rootId)
                        rootMenu = null
                    }) { Text("Remove", color = WaveCueColors.Error) }
                },
                dismissButton = {
                    TextButton(onClick = { rootMenu = null }) { Text("Cancel") }
                },
            )
        }
    }

    addTarget?.let { node ->
        AddToPlaylistDialog(
            trackName = node.name,
            playlists = playlists,
            onDismiss = { addTarget = null },
            onPick = { playlistId ->
                viewModel.addToPlaylist(playlistId, node)
                addTarget = null
            },
        )
    }
}

@Composable
private fun RootsHeader(rootCount: Int, onAdd: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 10.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Library", style = MaterialTheme.typography.titleLarge)
            Text(
                if (rootCount == 0) "No folders yet" else "$rootCount folder${if (rootCount == 1) "" else "s"}",
                style = MaterialTheme.typography.labelSmall,
                color = WaveCueColors.TextSecondary,
            )
        }
        IconButton(onClick = onAdd) {
            Icon(Icons.Filled.Add, contentDescription = "Add folder")
        }
    }
}

@Composable
private fun BrowserHeader(
    title: String,
    subtitle: String,
    folderCount: Int,
    trackCount: Int,
    onUp: () -> Unit,
    onRefresh: () -> Unit,
    onRemoveRoot: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 10.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onUp) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Up")
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "$subtitle · $folderCount folders · $trackCount tracks",
                style = MaterialTheme.typography.labelSmall,
                color = WaveCueColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
        }
        IconButton(onClick = onRemoveRoot) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Folder options")
        }
    }
}

@Composable
private fun RootRow(root: LibraryRoot, onOpen: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        color = WaveCueColors.Surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, WaveCueColors.Outline.copy(alpha = 0.7f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = WaveCueColors.Primary,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                root.name,
                style = MaterialTheme.typography.titleMedium,
                color = WaveCueColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Folder options",
                        tint = WaveCueColors.TextSecondary,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Remove from library") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = {
                            menu = false
                            onRemove()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NodeRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    name: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = WaveCueColors.PrimaryDim,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = WaveCueColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = WaveCueColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun TrackRow(
    node: DocumentNode,
    playing: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .testTag(TestTags.libraryTrack(node.name))
            .clickable(onClick = onPlay)
            .padding(start = 8.dp, end = 2.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = if (playing) WaveCueColors.Primary else WaveCueColors.TextSecondary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = node.name,
                style = MaterialTheme.typography.bodyMedium,
                color = if (playing) WaveCueColors.Primary else WaveCueColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                MetaChip(
                    if (node.name.substringAfterLast('.', "").lowercase() == "mp3") "MP3" else "WAV",
                    WaveCueColors.TextSecondary,
                )
                if (node.sizeBytes > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${"%.1f".format(node.sizeBytes / 1_048_576.0)} MB",
                        style = MaterialTheme.typography.labelSmall,
                        color = WaveCueColors.TextSecondary,
                    )
                }
            }
        }
        Box {
            IconButton(
                onClick = { menu = true },
                modifier = Modifier.testTag(TestTags.libraryTrackMenu(node.name)),
            ) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Track options",
                    tint = WaveCueColors.TextSecondary,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Play with folder queue") },
                    leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                    onClick = {
                        menu = false
                        onPlay()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Add to playlist…") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                    onClick = {
                        menu = false
                        onAddToPlaylist()
                    },
                )
            }
        }
    }
}

@Composable
private fun AddToPlaylistDialog(
    trackName: String,
    playlists: List<PlaylistSummary>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            Column {
                Text(
                    trackName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WaveCueColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(10.dp))
                if (playlists.isEmpty()) {
                    Text(
                        "No playlists yet. Create one from the Playlists tab first.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 300.dp)) {
                        items(playlists, key = { it.id }) { playlist ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(playlist.id) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    playlist.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = WaveCueColors.TextPrimary,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "${playlist.trackCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WaveCueColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
