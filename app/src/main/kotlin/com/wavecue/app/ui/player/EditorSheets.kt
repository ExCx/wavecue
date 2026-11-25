package com.wavecue.app.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.wavecue.app.ui.TestTags
import com.wavecue.app.ui.components.MetaChip
import com.wavecue.app.ui.theme.WaveCueColors
import com.wavecue.core.model.AppSettings
import com.wavecue.core.model.FlagItem
import com.wavecue.core.model.NoteItem
import com.wavecue.core.model.SilenceRegion
import com.wavecue.core.model.TimeFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlagEditorSheet(
    initial: FlagItem?,
    currentPositionMs: Long,
    suggestedIndex: Int,
    onDismiss: () -> Unit,
    onSave: (timeMs: Long, title: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember(initial?.id) {
        mutableStateOf(initial?.title ?: "Flag $suggestedIndex")
    }
    var timeText by remember(initial?.id) {
        mutableStateOf(TimeFormat.timecode(initial?.timeMs ?: currentPositionMs))
    }
    val parsed = TimeFormat.parseTimecode(timeText)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WaveCueColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                if (initial == null) "New flag" else "Edit flag",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.FlagTitle),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = timeText,
                onValueChange = { timeText = it },
                label = { Text("Timecode") },
                singleLine = true,
                isError = parsed == null,
                supportingText = {
                    if (parsed == null) Text("Use m:ss.mmm or h:mm:ss.mmm")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.FlagTime),
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag(TestTags.FlagDelete)) {
                        Text("Delete", color = WaveCueColors.Error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        val time = parsed ?: return@Button
                        onSave(time, title.ifBlank { "Flag" })
                    },
                    enabled = parsed != null,
                    modifier = Modifier.testTag(TestTags.FlagSave),
                ) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorSheet(
    initial: NoteItem?,
    currentPositionMs: Long,
    onDismiss: () -> Unit,
    onSave: (timeMs: Long, body: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var body by remember(initial?.id) { mutableStateOf(initial?.body.orEmpty()) }
    var timeText by remember(initial?.id) {
        mutableStateOf(TimeFormat.timecode(initial?.timeMs ?: currentPositionMs))
    }
    val parsed = TimeFormat.parseTimecode(timeText)

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WaveCueColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
                .imePadding()
                .navigationBarsPadding(),
        ) {
            Text(
                if (initial == null) "New note" else "Edit note",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Note") },
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth().testTag(TestTags.NoteBody),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = timeText,
                onValueChange = { timeText = it },
                label = { Text("Timecode") },
                singleLine = true,
                isError = parsed == null,
                supportingText = {
                    if (parsed == null) Text("Use m:ss.mmm or h:mm:ss.mmm")
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth().testTag(TestTags.NoteTime),
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag(TestTags.NoteDelete)) {
                        Text("Delete", color = WaveCueColors.Error)
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(6.dp))
                Button(
                    onClick = {
                        val time = parsed ?: return@Button
                        onSave(time, body)
                    },
                    enabled = parsed != null && body.isNotBlank(),
                    modifier = Modifier.testTag(TestTags.NoteSave),
                ) { Text("Save") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SilenceReviewSheet(
    regions: List<SilenceRegion>,
    settings: AppSettings,
    scanning: Boolean = false,
    onDismiss: () -> Unit,
    onAccept: (List<SilenceRegion>) -> Unit,
) {
    val selected = remember(regions) { mutableStateListOf(*regions.indices.toList().toTypedArray()) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = WaveCueColors.Surface) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                if (scanning) "Scanning for silence" else "Silence detected",
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(4.dp))
            if (scanning) {
                Text(
                    "Measuring quiet passages against the current threshold. " +
                        "Nothing is stored until you accept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WaveCueColors.TextSecondary,
                )
                Spacer(Modifier.height(16.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Text(
                    "${regions.size} region${if (regions.size == 1) "" else "s"} under " +
                        "${settings.silenceThresholdDb.toInt()} dB for at least " +
                        "${settings.silenceMinDurationMs / 1000.0}s. " +
                        "Nothing is stored until you accept.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = WaveCueColors.TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                if (regions.isEmpty()) {
                    Text(
                        "No silent regions were found with the current threshold.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextSecondary,
                    )
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(regions.size) { index ->
                            val region = regions[index]
                            val checked = index in selected
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = { value ->
                                        if (value) selected.add(index) else selected.remove(index)
                                    },
                                )
                                Text(
                                    text = "${TimeFormat.timecode(region.startMs)}  →  " +
                                        TimeFormat.timecode(region.endMs),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontFamily = FontFamily.Monospace,
                                )
                                Spacer(Modifier.weight(1f))
                                MetaChip("${(region.durationMs / 1000.0).let { "%.1fs".format(it) }}")
                            }
                            HorizontalDivider(color = WaveCueColors.Outline.copy(alpha = 0.35f))
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        onAccept(selected.sorted().map { regions[it] })
                    },
                    enabled = !scanning && selected.isNotEmpty(),
                    modifier = Modifier.testTag(TestTags.SilenceCreate),
                ) {
                    Text(
                        if (scanning) "Scanning…"
                        else "Create ${selected.size} flag${if (selected.size == 1) "" else "s"}",
                    )
                }
            }
        }
    }
}
