package com.wavecue.app.ui.playlists

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wavecue.app.ui.components.EmptyState
import com.wavecue.app.ui.components.MetaChip
import com.wavecue.app.ui.theme.WaveCueColors
import com.wavecue.core.model.PlaylistSortMode
import com.wavecue.core.model.PlaylistSummary
import com.wavecue.core.model.TimeFormat

@Composable
fun PlaylistsScreen(
    onOpenPlaylist: (Long) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val playlists by viewModel.summaries.collectAsStateWithLifecycle()
    var createOpen by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<PlaylistSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<PlaylistSummary?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 10.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Playlists", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (playlists.isEmpty()) "None yet" else "${playlists.size} playlist${if (playlists.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = WaveCueColors.TextSecondary,
                )
            }
            IconButton(onClick = { createOpen = true }) {
                Icon(Icons.Filled.Add, contentDescription = "New playlist")
            }
        }

        if (playlists.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                title = "Build a playlist",
                message = "Create a playlist, then add tracks from the Library tab. Alphabetical order is the default and can be shuffled any time.",
                actionLabel = "New playlist",
                onAction = { createOpen = true },
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(playlists.size, key = { playlists[it].id }) { index ->
                    val playlist = playlists[index]
                    PlaylistRow(
                        playlist = playlist,
                        onOpen = { onOpenPlaylist(playlist.id) },
                        onRename = { renameTarget = playlist },
                        onDelete = { deleteTarget = playlist },
                    )
                }
            }
        }
    }

    if (createOpen) {
        NameDialog(
            title = "New playlist",
            initial = "",
            confirmLabel = "Create",
            onDismiss = { createOpen = false },
            onConfirm = {
                viewModel.create(it)
                createOpen = false
            },
        )
    }

    renameTarget?.let { target ->
        NameDialog(
            title = "Rename playlist",
            initial = target.name,
            confirmLabel = "Rename",
            onDismiss = { renameTarget = null },
            onConfirm = {
                viewModel.rename(target.id, it)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete playlist?") },
            text = { Text("\"${target.name}\" will be deleted. Tracks and notes stay in the library.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(target.id)
                    deleteTarget = null
                }) { Text("Delete", color = WaveCueColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun PlaylistRow(
    playlist: PlaylistSummary,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
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
            Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = null,
                tint = WaveCueColors.PrimaryDim,
                modifier = Modifier.size(26.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = WaveCueColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${playlist.trackCount} track${if (playlist.trackCount == 1) "" else "s"}",
                        style = MaterialTheme.typography.labelSmall,
                        color = WaveCueColors.TextSecondary,
                    )
                    Spacer(Modifier.width(8.dp))
                    MetaChip(sortLabel(playlist.sortMode), WaveCueColors.TextSecondary)
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = "Playlist options",
                        tint = WaveCueColors.TextSecondary,
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename") },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    var renameOpen by remember { mutableStateOf(false) }
    var deleteOpen by remember { mutableStateOf(false) }

    val playlist = detail

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 10.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    playlist?.name ?: "Playlist",
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    playlist?.let { "${it.tracks.size} tracks · ${sortLabel(it.sortMode)}" } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = WaveCueColors.TextSecondary,
                )
            }
            IconButton(onClick = { renameOpen = true }) {
                Icon(Icons.Filled.Edit, contentDescription = "Rename")
            }
            IconButton(onClick = { deleteOpen = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Delete playlist", tint = WaveCueColors.TextSecondary)
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { viewModel.playAll() },
                enabled = !playlist?.tracks.isNullOrEmpty(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Play")
            }
            FilterChip(
                selected = playlist?.sortMode == PlaylistSortMode.ALPHABETICAL,
                onClick = { viewModel.alphabetical() },
                label = { Text("A–Z") },
                leadingIcon = { Icon(Icons.Filled.SortByAlpha, null, Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = WaveCueColors.PrimaryDim,
                    selectedLabelColor = WaveCueColors.TextPrimary,
                ),
            )
            FilterChip(
                selected = playlist?.sortMode == PlaylistSortMode.SHUFFLED,
                onClick = { viewModel.shuffle() },
                label = { Text("Shuffle") },
                leadingIcon = { Icon(Icons.Filled.Shuffle, null, Modifier.size(16.dp)) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = WaveCueColors.PrimaryDim,
                    selectedLabelColor = WaveCueColors.TextPrimary,
                ),
            )
        }

        val tracks = playlist?.tracks.orEmpty()
        if (tracks.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.PlaylistPlay,
                title = "This playlist is empty",
                message = "Open a track's menu in the Library tab and choose \"Add to playlist…\".",
            )
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp),
            ) {
                itemsIndexed(tracks, key = { _, item -> item.entryId }) { index, item ->
                    TrackEntryRow(
                        index = index,
                        name = item.name,
                        durationMs = item.durationMs,
                        canMoveUp = index > 0,
                        canMoveDown = index < tracks.lastIndex,
                        onPlay = { viewModel.play(index) },
                        onMoveUp = { viewModel.move(item.entryId, -1) },
                        onMoveDown = { viewModel.move(item.entryId, 1) },
                        onRemove = { viewModel.remove(item.entryId) },
                    )
                }
            }
        }
    }

    if (renameOpen) {
        NameDialog(
            title = "Rename playlist",
            initial = playlist?.name.orEmpty(),
            confirmLabel = "Rename",
            onDismiss = { renameOpen = false },
            onConfirm = {
                viewModel.rename(it)
                renameOpen = false
            },
        )
    }

    if (deleteOpen) {
        AlertDialog(
            onDismissRequest = { deleteOpen = false },
            title = { Text("Delete playlist?") },
            text = { Text("Only the playlist is removed. Tracks, flags and notes stay in the library.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteOpen = false
                    viewModel.delete(onBack)
                }) { Text("Delete", color = WaveCueColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteOpen = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun TrackEntryRow(
    index: Int,
    name: String,
    durationMs: Long,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${index + 1}",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = WaveCueColors.TextSecondary,
            modifier = Modifier.width(26.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyMedium,
                color = WaveCueColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (durationMs > 0) {
                Text(
                    TimeFormat.duration(durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = WaveCueColors.TextSecondary,
                )
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Track options",
                    tint = WaveCueColors.TextSecondary,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text("Move up") },
                    leadingIcon = { Icon(Icons.Filled.ArrowUpward, null) },
                    enabled = canMoveUp,
                    onClick = {
                        menu = false
                        onMoveUp()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Move down") },
                    leadingIcon = { Icon(Icons.Filled.ArrowDownward, null) },
                    enabled = canMoveDown,
                    onClick = {
                        menu = false
                        onMoveDown()
                    },
                )
                DropdownMenuItem(
                    text = { Text("Remove") },
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

@Composable
private fun NameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun sortLabel(mode: PlaylistSortMode): String = when (mode) {
    PlaylistSortMode.ALPHABETICAL -> "A–Z"
    PlaylistSortMode.MANUAL -> "Manual"
    PlaylistSortMode.SHUFFLED -> "Shuffled"
}
