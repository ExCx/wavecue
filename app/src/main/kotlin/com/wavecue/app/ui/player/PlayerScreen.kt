package com.wavecue.app.ui.player

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.NoteAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wavecue.app.ui.TestTags
import com.wavecue.app.ui.components.EmptyState
import com.wavecue.app.ui.components.InfoRow
import com.wavecue.app.ui.components.MetaChip
import com.wavecue.app.ui.theme.WaveCueColors
import com.wavecue.core.model.FlagItem
import com.wavecue.core.model.NoteItem
import com.wavecue.core.model.PlaybackState
import com.wavecue.core.model.TimeFormat
import kotlin.math.max

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(
    onOpenLibrary: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val playbackState by viewModel.state.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val media by viewModel.media.collectAsStateWithLifecycle()
    val track by viewModel.track.collectAsStateWithLifecycle()
    val current by viewModel.current.collectAsStateWithLifecycle()
    val queueLabel by viewModel.queueLabel.collectAsStateWithLifecycle()
    val waveform by viewModel.waveform.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val flags by viewModel.flags.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val pendingSilence by viewModel.pendingSilence.collectAsStateWithLifecycle()
    val detecting by viewModel.detecting.collectAsStateWithLifecycle()
    val diagnostics by viewModel.diagnostics.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()

    var showFlagSheet by remember { mutableStateOf(false) }
    var editingFlag by remember { mutableStateOf<FlagItem?>(null) }
    var showNoteSheet by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteItem?>(null) }
    var reviewSilence by remember { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(0) }

    val timelineMs = max(
        media?.durationMs ?: 0L,
        max(track?.durationMs ?: 0L, waveform?.durationMs ?: 0L),
    ).coerceAtLeast(1L)

    val viewport = remember(current?.trackId) { WaveformViewportState() }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.clearMessage()
    }

    val exportNotes = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri -> uri?.let(viewModel::exportNotes) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(10.dp))
            TrackHeader(
                title = track?.name ?: media?.name ?: "Nothing loaded",
                queueLabel = queueLabel,
                codec = media?.codec ?: track?.codec,
                sampleRate = media?.sampleRate ?: track?.sampleRate ?: 0,
                bitDepth = media?.bitDepth ?: track?.bitDepth ?: 0,
                channels = media?.channelCount ?: track?.channelCount ?: 0,
            )

            Spacer(Modifier.height(12.dp))
            Surface(
                color = WaveCueColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, WaveCueColors.Outline.copy(alpha = 0.8f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .testTag(TestTags.PlayerWaveform),
                    ) {
                        WaveformView(
                            waveform = waveform,
                            durationMs = timelineMs,
                            positionMs = positionMs,
                            flags = flags,
                            notes = notes,
                            pendingSilence = pendingSilence,
                            viewport = viewport,
                            playing = playbackState == PlaybackState.PLAYING,
                            followPlayhead = settings.followPlayhead,
                            onSeek = viewModel::seekTo,
                            onFlagTap = {
                                editingFlag = it
                                showFlagSheet = true
                            },
                            onNoteTap = {
                                editingNote = it
                                showNoteSheet = true
                            },
                            modifier = Modifier.fillMaxSize(),
                        )
                        if (waveform == null) {
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                if (progress != null) {
                                    CircularProgressIndicator(
                                        progress = { progress ?: 0f },
                                        modifier = Modifier.size(28.dp),
                                        strokeWidth = 2.dp,
                                        color = WaveCueColors.Primary,
                                    )
                                    Spacer(Modifier.height(8.dp))
                                    Text(
                                        "Analysing ${((progress ?: 0f) * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WaveCueColors.TextSecondary,
                                    )
                                } else if (current != null) {
                                    Text(
                                        "Waveform unavailable",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WaveCueColors.TextSecondary,
                                    )
                                    TextButton(onClick = viewModel::retryAnalysis) { Text("Retry") }
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = TimeFormat.timecode(viewportStartMs(timelineMs, viewport)),
                            style = MaterialTheme.typography.labelSmall,
                            color = WaveCueColors.TextSecondary,
                            modifier = Modifier.testTag(TestTags.ViewportStart),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${viewport.zoom.toInt().coerceAtLeast(1)}x",
                            style = MaterialTheme.typography.labelSmall,
                            color = WaveCueColors.TextSecondary,
                            modifier = Modifier.testTag(TestTags.ZoomLabel),
                        )
                        IconButton(
                            onClick = { viewport.zoomOut((positionMs.toFloat() / timelineMs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)) },
                            enabled = viewport.zoom > 1f,
                            modifier = Modifier.testTag(TestTags.ZoomOut),
                        ) {
                            Icon(Icons.Filled.ZoomOut, contentDescription = "Zoom out")
                        }
                        IconButton(
                            onClick = { viewport.zoomIn((positionMs.toFloat() / timelineMs.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)) },
                            enabled = viewport.zoom < viewport.maxZoom,
                            modifier = Modifier.testTag(TestTags.ZoomIn),
                        ) {
                            Icon(Icons.Filled.ZoomIn, contentDescription = "Zoom in")
                        }
                        TextButton(
                            onClick = { viewport.fit() },
                            modifier = Modifier.testTag(TestTags.ZoomFit),
                        ) { Text("Fit") }
                        if (viewport.suspendFollow) {
                            TextButton(onClick = { viewport.resumeFollow() }) {
                                Text("Follow", color = WaveCueColors.Secondary)
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = TimeFormat.timecode(positionMs),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            color = WaveCueColors.Primary,
                            modifier = Modifier.testTag(TestTags.PlayerPosition),
                        )
                        Text(
                            text = " / ${TimeFormat.timecode(timelineMs)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                            color = WaveCueColors.TextSecondary,
                            modifier = Modifier.testTag(TestTags.PlayerDuration),
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            "drag to pan · pinch to zoom",
                            style = MaterialTheme.typography.labelSmall,
                            color = WaveCueColors.TextSecondary.copy(alpha = 0.7f),
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            TransportRow(
                state = playbackState,
                onPrevious = viewModel::previous,
                onBack5 = { viewModel.seekBy(-5_000) },
                onToggle = viewModel::toggle,
                onForward5 = { viewModel.seekBy(5_000) },
                onNext = viewModel::next,
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AssistChip(
                    onClick = {
                        editingFlag = null
                        showFlagSheet = true
                    },
                    label = { Text("Flag here") },
                    leadingIcon = { Icon(Icons.Filled.Add, null, Modifier.size(16.dp)) },
                    enabled = current != null,
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = WaveCueColors.TextPrimary,
                    ),
                )
                AssistChip(
                    onClick = {
                        editingNote = null
                        showNoteSheet = true
                    },
                    label = { Text("Note here") },
                    leadingIcon = { Icon(Icons.Filled.NoteAlt, null, Modifier.size(16.dp)) },
                    enabled = current != null,
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = WaveCueColors.TextPrimary,
                    ),
                )
                AssistChip(
                    onClick = {
                        viewModel.detectSilence()
                        reviewSilence = true
                    },
                    label = { Text(if (detecting) "Scanning…" else "Find gaps") },
                    leadingIcon = { Icon(Icons.Filled.GraphicEq, null, Modifier.size(16.dp)) },
                    enabled = waveform != null && !detecting,
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = WaveCueColors.TextPrimary,
                    ),
                )
                AssistChip(
                    onClick = {
                        val name = (track?.name ?: "track").substringBeforeLast('.')
                            .replace(Regex("[^A-Za-z0-9 _-]"), "_")
                        exportNotes.launch("$name-notes.md")
                    },
                    label = { Text("Export notes") },
                    leadingIcon = { Icon(Icons.Filled.FileDownload, null, Modifier.size(16.dp)) },
                    enabled = current != null,
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = WaveCueColors.TextPrimary,
                    ),
                )
            }

            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = section == 0,
                    onClick = { section = 0 },
                    label = { Text("Segments ${flags.size}") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = WaveCueColors.PrimaryDim,
                        selectedLabelColor = WaveCueColors.TextPrimary,
                    ),
                )
                FilterChip(
                    selected = section == 1,
                    onClick = { section = 1 },
                    label = { Text("Notes ${notes.size}") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = WaveCueColors.PrimaryDim,
                        selectedLabelColor = WaveCueColors.TextPrimary,
                    ),
                )
                FilterChip(
                    selected = section == 2,
                    onClick = { section = 2 },
                    label = { Text("Output") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = WaveCueColors.PrimaryDim,
                        selectedLabelColor = WaveCueColors.TextPrimary,
                    ),
                )
            }

            Box(Modifier.weight(1f).heightIn(min = 160.dp)) {
                when (section) {
                    0 -> FlagList(
                        flags = flags,
                        onSelect = {
                            editingFlag = it
                            showFlagSheet = true
                        },
                        onSeek = { viewModel.seekTo(it.timeMs) },
                        onDelete = { viewModel.deleteFlag(it.id) },
                        onAdd = {
                            editingFlag = null
                            showFlagSheet = true
                        },
                    )
                    1 -> NoteList(
                        notes = notes,
                        onSelect = {
                            editingNote = it
                            showNoteSheet = true
                        },
                        onSeek = { viewModel.seekTo(it.timeMs) },
                        onDelete = { viewModel.deleteNote(it.id) },
                        onAdd = {
                            editingNote = null
                            showNoteSheet = true
                        },
                    )
                    else -> DiagnosticsPanel(
                        diagnostics = diagnostics,
                        settings = settings,
                        trackCodec = track?.codec ?: "",
                        trackRate = track?.sampleRate ?: 0,
                        trackBitDepth = track?.bitDepth ?: 0,
                        trackChannels = track?.channelCount ?: 0,
                        onRetry = viewModel::retryAnalysis,
                        onOpenLibrary = onOpenLibrary,
                    )
                }
            }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    if (showFlagSheet) {
        val editing = editingFlag
        // Freeze the playhead when the sheet opens; otherwise every position tick
        // would re-run the initializer and wipe what the user is typing.
        val anchorMs = remember { positionMs }
        FlagEditorSheet(
            initial = editing,
            currentPositionMs = anchorMs,
            suggestedIndex = flags.size + 1,
            onDismiss = { showFlagSheet = false },
            onSave = { time, title ->
                if (editing != null) viewModel.updateFlag(editing.id, time, title)
                else viewModel.addFlagAt(time, title)
                showFlagSheet = false
            },
            onDelete = editing?.let {
                {
                    viewModel.deleteFlag(it.id)
                    showFlagSheet = false
                }
            },
        )
    }

    if (showNoteSheet) {
        val editing = editingNote
        val anchorMs = remember { positionMs }
        NoteEditorSheet(
            initial = editing,
            currentPositionMs = anchorMs,
            onDismiss = { showNoteSheet = false },
            onSave = { time, body ->
                if (editing != null) viewModel.updateNote(editing.id, time, body)
                else viewModel.addNoteAt(time, body)
                showNoteSheet = false
            },
            onDelete = editing?.let {
                {
                    viewModel.deleteNote(it.id)
                    showNoteSheet = false
                }
            },
        )
    }

    if (reviewSilence) {
        SilenceReviewSheet(
            regions = pendingSilence,
            settings = settings,
            scanning = detecting,
            onDismiss = {
                reviewSilence = false
                viewModel.dismissSilence()
            },
            onAccept = { selected ->
                viewModel.acceptSilence(selected)
                reviewSilence = false
            },
        )
    }

}

@Composable
private fun TrackHeader(
    title: String,
    queueLabel: String?,
    codec: String?,
    sampleRate: Int,
    bitDepth: Int,
    channels: Int,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = WaveCueColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (!codec.isNullOrBlank()) MetaChip(codec)
                if (sampleRate > 0) {
                    MetaChip("${"%.1f".format(sampleRate / 1000.0)} kHz", WaveCueColors.Primary)
                }
                if (bitDepth > 0) MetaChip("$bitDepth-bit", WaveCueColors.Primary)
                if (channels > 0) {
                    MetaChip(if (channels == 1) "mono" else "$channels ch", WaveCueColors.TextSecondary)
                }
            }
        }
        if (queueLabel != null) {
            Text(
                text = queueLabel,
                style = MaterialTheme.typography.labelSmall,
                color = WaveCueColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun TransportRow(
    state: PlaybackState,
    onPrevious: () -> Unit,
    onBack5: () -> Unit,
    onToggle: () -> Unit,
    onForward5: () -> Unit,
    onNext: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier.size(52.dp).testTag(TestTags.TransportPrevious),
        ) {
            Icon(
                Icons.Filled.SkipPrevious,
                contentDescription = "Previous track",
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(
            onClick = onBack5,
            modifier = Modifier.size(52.dp).testTag(TestTags.TransportBack5),
        ) {
            Icon(
                Icons.Filled.Replay5,
                contentDescription = "Back 5 seconds",
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = onToggle,
            shape = CircleShape,
            modifier = Modifier.size(64.dp).testTag(TestTags.TransportToggle),
        ) {
            if (state == PlaybackState.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.dp,
                    color = WaveCueColors.Background,
                )
            } else {
                Icon(
                    imageVector = if (state == PlaybackState.PLAYING) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state == PlaybackState.PLAYING) "Pause" else "Play",
                    modifier = Modifier.size(34.dp),
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = onForward5,
            modifier = Modifier.size(52.dp).testTag(TestTags.TransportForward5),
        ) {
            Icon(
                Icons.Filled.Forward5,
                contentDescription = "Forward 5 seconds",
                modifier = Modifier.size(26.dp),
            )
        }
        IconButton(
            onClick = onNext,
            modifier = Modifier.size(52.dp).testTag(TestTags.TransportNext),
        ) {
            Icon(
                Icons.Filled.SkipNext,
                contentDescription = "Next track",
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

@Composable
private fun FlagList(
    flags: List<FlagItem>,
    onSelect: (FlagItem) -> Unit,
    onSeek: (FlagItem) -> Unit,
    onDelete: (FlagItem) -> Unit,
    onAdd: () -> Unit,
) {
    if (flags.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.Flag,
            title = "No segments yet",
            message = "Flag the playhead, or scan the track for silent gaps and accept the ones you want.",
            actionLabel = "Add a flag",
            onAction = onAdd,
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
    ) {
        items(flags, key = { it.id }) { flag ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(flag) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = TimeFormat.timecode(flag.timeMs),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    color = WaveCueColors.Secondary,
                    modifier = Modifier.width(84.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = flag.title,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (flag.automatic) {
                        Text(
                            "auto-detected",
                            style = MaterialTheme.typography.labelSmall,
                            color = WaveCueColors.TextSecondary,
                        )
                    }
                }
                IconButton(onClick = { onSeek(flag) }) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = "Play from flag",
                        tint = WaveCueColors.Primary,
                    )
                }
                IconButton(onClick = { onDelete(flag) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete flag",
                        tint = WaveCueColors.TextSecondary,
                    )
                }
            }
            HorizontalDivider(color = WaveCueColors.Outline.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun NoteList(
    notes: List<NoteItem>,
    onSelect: (NoteItem) -> Unit,
    onSeek: (NoteItem) -> Unit,
    onDelete: (NoteItem) -> Unit,
    onAdd: () -> Unit,
) {
    if (notes.isEmpty()) {
        EmptyState(
            icon = Icons.Filled.NoteAlt,
            title = "No notes yet",
            message = "Capture a thought at the current playhead. Notes are stored in the app database and export as Markdown.",
            actionLabel = "Add a note",
            onAction = onAdd,
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp),
    ) {
        items(notes, key = { it.id }) { note ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(note) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    text = TimeFormat.timecode(note.timeMs),
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = WaveCueColors.Note,
                    modifier = Modifier.width(84.dp).padding(top = 2.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = note.body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextPrimary,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { onSeek(note) }) {
                    Icon(
                        Icons.Filled.PlayCircle,
                        contentDescription = "Play from note",
                        tint = WaveCueColors.Primary,
                    )
                }
                IconButton(onClick = { onDelete(note) }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Delete note",
                        tint = WaveCueColors.TextSecondary,
                    )
                }
            }
            HorizontalDivider(color = WaveCueColors.Outline.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun DiagnosticsPanel(
    diagnostics: com.wavecue.core.model.PlaybackDiagnostics,
    settings: com.wavecue.core.model.AppSettings,
    trackCodec: String,
    trackRate: Int,
    trackBitDepth: Int,
    trackChannels: Int,
    onRetry: () -> Unit,
    onOpenLibrary: () -> Unit,
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Surface(
                color = WaveCueColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, WaveCueColors.Outline.copy(alpha = 0.8f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Audio path", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Sink", diagnostics.sinkName)
                    InfoRow("Source", diagnostics.sourceCodec.ifBlank { trackCodec.ifBlank { "—" } })
                    InfoRow(
                        "Source format",
                        if (diagnostics.sourceSampleRate > 0) {
                            "${diagnostics.sourceSampleRate} Hz · ${diagnostics.sourceBitDepth}-bit · ${diagnostics.sourceChannels} ch"
                        } else if (trackRate > 0) {
                            "$trackRate Hz · $trackBitDepth-bit · $trackChannels ch"
                        } else {
                            "—"
                        },
                    )
                    InfoRow(
                        "Output format",
                        if (diagnostics.outputSampleRate > 0) {
                            "${diagnostics.outputSampleRate} Hz · ${diagnostics.outputEncoding}"
                        } else {
                            "—"
                        },
                    )
                    InfoRow("Sharing", diagnostics.sharingMode.ifBlank { "—" })
                    InfoRow("Performance", diagnostics.performanceMode.ifBlank { "—" })
                    InfoRow("Buffer", if (diagnostics.bufferFrames > 0) "${diagnostics.bufferFrames} frames" else "—")
                    InfoRow(
                        "Latency",
                        if (diagnostics.latencyMs > 0) "${"%.1f".format(diagnostics.latencyMs)} ms" else "—",
                    )
                    InfoRow("Underruns", diagnostics.xRuns.toString())
                    InfoRow(
                        "Signal",
                        when {
                            diagnostics.bitPerfect -> "bit-perfect"
                            diagnostics.resampled -> "resampled"
                            diagnostics.sinkName == "idle" -> "idle"
                            else -> "unchanged rate"
                        },
                        valueColor = when {
                            diagnostics.bitPerfect -> WaveCueColors.Primary
                            diagnostics.resampled -> WaveCueColors.Secondary
                            else -> WaveCueColors.TextPrimary
                        },
                    )
                    if (diagnostics.message.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            diagnostics.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (diagnostics.bitPerfect) WaveCueColors.Primary else WaveCueColors.TextSecondary,
                        )
                    }
                }
            }
        }
        item {
            Surface(
                color = WaveCueColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, WaveCueColors.Outline.copy(alpha = 0.8f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Bit-perfect USB", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (settings.bitPerfectUsb) {
                            if (diagnostics.bitPerfect) {
                                "Active. Android 14 mixer attributes accepted the source format for a USB output, so the mixer, resampler and volume stage are bypassed."
                            } else {
                                "Requested, but no USB device currently accepts a bit-perfect mixer attribute matching this format. Playback is using the next most faithful path listed above — nothing is reported as bit-perfect unless it is."
                            }
                        } else {
                            "Disabled in Settings. Enable it to attempt Android 14+ USB bit-perfect output; a matching USB DAC and exact format are required."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRetry) { Text("Re-analyse waveform") }
                        TextButton(onClick = onOpenLibrary) { Text("Library") }
                    }
                }
            }
        }
        item {
            Surface(
                color = WaveCueColors.Surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, WaveCueColors.Outline.copy(alpha = 0.8f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(14.dp)) {
                    Text("Playback practice", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    val lines = listOf(
                        "No EQ, compression, limiter, or volume normalisation is applied anywhere in the chain.",
                        "WAV is decoded natively (PCM 8/16/24/32 and float); MP3 is decoded by the platform codec.",
                        "The output stream always requests the source sample rate and channel count.",
                        "Pausing flushes the sink and re-seeks, so resume is sample-accurate.",
                        "No INTERNET permission: nothing leaves the device unless you export it.",
                    )
                    lines.forEach { line ->
                        Text(
                            "· $line",
                            style = MaterialTheme.typography.bodyMedium,
                            color = WaveCueColors.TextSecondary,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun viewportStartMs(timelineMs: Long, viewport: WaveformViewportState): Long =
    (viewport.startFraction * timelineMs).toLong()
