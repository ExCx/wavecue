package com.wavecue.app.ui.settings

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wavecue.app.BuildConfig
import com.wavecue.app.ui.TestTags
import com.wavecue.app.ui.components.SectionCard
import com.wavecue.app.ui.theme.WaveCueColors

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        val text = message ?: return@LaunchedEffect
        snackbar.showSnackbar(text)
        viewModel.clearMessage()
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> uri?.let(viewModel::exportJson) }

    var buffer by remember(settings.bufferMs) { mutableFloatStateOf(settings.bufferMs.toFloat()) }
    var threshold by remember(settings.silenceThresholdDb) {
        mutableFloatStateOf(settings.silenceThresholdDb)
    }
    var minDuration by remember(settings.silenceMinDurationMs) {
        mutableLongStateOf(settings.silenceMinDurationMs)
    }

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Text(
                "Playback fidelity, silence detection and data",
                style = MaterialTheme.typography.labelSmall,
                color = WaveCueColors.TextSecondary,
            )
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 96.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SectionCard(title = "Playback") {
                    Text(
                        "Buffer ${buffer.toInt()} ms",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextPrimary,
                    )
                    Text(
                        "Larger buffers survive scheduling jitter; smaller buffers cut latency. Audio is never altered either way.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WaveCueColors.TextSecondary,
                    )
                    Slider(
                        value = buffer,
                        onValueChange = { buffer = it },
                        onValueChangeFinished = { viewModel.setBufferMs(buffer.toInt()) },
                        valueRange = 50f..1500f,
                        steps = 28,
                    )
                    ToggleRow(
                        title = "Exclusive AAudio stream",
                        subtitle = "Ask the platform for a direct, low-latency path at the source rate. Falls back cleanly.",
                        checked = settings.exclusiveMode,
                        onCheckedChange = viewModel::setExclusiveMode,
                        tag = TestTags.SettingsExclusive,
                    )
                    ToggleRow(
                        title = "USB bit-perfect (Android 14+)",
                        subtitle = if (Build.VERSION.SDK_INT >= 34) {
                            "Attempt mixer bypass when a USB output exactly matches the source format. Diagnostics always report the real path."
                        } else {
                            "Requires Android 14 or newer. This device uses the same-rate shared or exclusive path."
                        },
                        checked = settings.bitPerfectUsb,
                        enabled = Build.VERSION.SDK_INT >= 34,
                        onCheckedChange = viewModel::setBitPerfectUsb,
                        tag = TestTags.SettingsBitPerfect,
                    )
                    ToggleRow(
                        title = "Follow playhead",
                        subtitle = "Page the waveform when the playhead leaves the visible window.",
                        checked = settings.followPlayhead,
                        onCheckedChange = viewModel::setFollowPlayhead,
                        tag = TestTags.SettingsFollowPlayhead,
                    )
                }
            }

            item {
                SectionCard(title = "Silence detection") {
                    Text(
                        "Threshold ${threshold.toInt()} dBFS",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextPrimary,
                    )
                    Slider(
                        value = threshold,
                        onValueChange = { threshold = it },
                        onValueChangeFinished = { viewModel.setSilenceThreshold(threshold) },
                        valueRange = -80f..-20f,
                        steps = 59,
                    )
                    Text(
                        "Minimum gap ${"%.1f".format(minDuration / 1000.0)} s",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextPrimary,
                    )
                    Slider(
                        value = minDuration.toFloat(),
                        onValueChange = { minDuration = it.toLong() },
                        onValueChangeFinished = { viewModel.setSilenceMinDuration(minDuration) },
                        valueRange = 200f..15000f,
                        steps = 147,
                    )
                    ToggleRow(
                        title = "Include lead-in and tail silence",
                        subtitle = "Off by default, because album fades often touch the edges.",
                        checked = settings.includeEdgeSilence,
                        onCheckedChange = viewModel::setIncludeEdgeSilence,
                        tag = TestTags.SettingsIncludeEdges,
                    )
                    Text(
                        "Detection runs on the analysed peak envelope. Results are always reviewed before flags are created, then titled Gap 1, Gap 2 and so on.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WaveCueColors.TextSecondary,
                    )
                }
            }

            item {
                SectionCard(title = "Analysis") {
                    Text(
                        "Waveforms are cached as compact peak pyramids next to the app database. Changing silence settings never requires re-analysis.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextSecondary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = viewModel::clearCache) {
                        Text("Clear waveform cache")
                    }
                }
            }

            item {
                SectionCard(title = "Data") {
                    Text(
                        "Flags and notes live in the app database and are never written next to your audio files. Export creates a single JSON document through Android's file picker.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { exportLauncher.launch("wavecue-backup.json") }) {
                        Text("Export library as JSON")
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Per-track notes also export as Markdown from the Player tab.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WaveCueColors.TextSecondary,
                    )
                }
            }

            item {
                SectionCard(title = "About") {
                    Text(
                        "WaveCue ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = WaveCueColors.TextPrimary,
                    )
                    Text(
                        "Android 8.0+ · no network permission · WAV + MP3 · local-only annotations",
                        style = MaterialTheme.typography.labelSmall,
                        color = WaveCueColors.TextSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "WaveCue is a working title; confirm the name before publishing to a store.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WaveCueColors.TextSecondary,
                    )
                }
            }
        }
    }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) WaveCueColors.TextPrimary else WaveCueColors.TextSecondary,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = WaveCueColors.TextSecondary,
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
}
