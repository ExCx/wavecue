package com.wavecue.app.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.wavecue.app.ui.theme.WaveCueColors
import com.wavecue.core.analysis.WaveformData
import com.wavecue.core.model.FlagItem
import com.wavecue.core.model.NoteItem
import com.wavecue.core.model.SilenceRegion
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Zoom/scroll state for the waveform. Zoom is expressed in viewports: 1 shows the
 * whole track, 64 shows one sixty-fourth. Scroll is the fraction of the timeline at
 * the left edge; the player recreates this state per track and the activity keeps it
 * across configuration changes.
 */
@Stable
class WaveformViewportState {
    var zoom by mutableFloatStateOf(1f)
        private set
    var startFraction by mutableFloatStateOf(0f)
        private set
    var suspendFollow by mutableStateOf(false)

    val maxZoom: Float = 1024f

    fun setZoom(value: Float, anchorFraction: Float = 0.5f) {
        val old = zoom
        val next = value.coerceIn(1f, maxZoom)
        if (abs(next - old) < 0.0001f) return
        val anchor = anchorFraction.coerceIn(0f, 1f)
        val anchorMs = startFraction + (1f / old) * anchor
        val visible = 1f / next
        startFraction = (anchorMs - visible * anchor).coerceIn(0f, 1f - visible)
        zoom = next
    }

    fun zoomIn(anchor: Float = 0.5f) = setZoom(zoom * 2f, anchor)
    fun zoomOut(anchor: Float = 0.5f) = setZoom(zoom / 2f, anchor)

    fun fit() {
        zoom = 1f
        startFraction = 0f
    }

    fun panBy(fractionDelta: Float) {
        val visible = 1f / zoom
        startFraction = (startFraction - fractionDelta).coerceIn(0f, 1f - visible)
        suspendFollow = true
    }

    fun centerOn(fraction: Float, anchor: Float = 0.5f) {
        val visible = 1f / zoom
        startFraction = (fraction - visible * anchor).coerceIn(0f, 1f - visible)
    }

    /** Auto-page only when the playhead leaves the viewport, never while browsing. */
    fun follow(positionFraction: Float) {
        if (suspendFollow) return
        val visible = 1f / zoom
        val x = (positionFraction - startFraction) / visible
        if (x < 0.03f || x > 0.94f) centerOn(positionFraction, 0.38f)
    }

    fun resumeFollow() {
        suspendFollow = false
    }
}

@Composable
fun WaveformView(
    waveform: WaveformData?,
    durationMs: Long,
    positionMs: Long,
    flags: List<FlagItem>,
    notes: List<NoteItem>,
    pendingSilence: List<SilenceRegion>,
    viewport: WaveformViewportState,
    playing: Boolean,
    followPlayhead: Boolean = true,
    onSeek: (Long) -> Unit,
    onFlagTap: (FlagItem) -> Unit,
    onNoteTap: (NoteItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timelineMs = max(durationMs, waveform?.durationMs ?: 0L).coerceAtLeast(1L)
    val positionFraction = (positionMs.toFloat() / timelineMs).coerceIn(0f, 1f)

    LaunchedEffect(positionMs, playing, followPlayhead, viewport.zoom) {
        if (playing && followPlayhead) viewport.follow(positionFraction)
    }

    Canvas(
        modifier = modifier
            .pointerInput(waveform, timelineMs) {
                detectTapGestures { offset ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    val visible = 1f / viewport.zoom
                    val startMs = (viewport.startFraction * timelineMs).toLong()
                    fun msAt(x: Float): Long =
                        (startMs + (x / width) * timelineMs * visible).toLong().coerceIn(0, timelineMs)

                    val flagRow = 30.dp.toPx()
                    if (offset.y <= flagRow) {
                        val nearest = flags.minByOrNull { abs(msAt(offset.x) - it.timeMs) }
                        if (nearest != null) {
                            val nearestX = xForMs(nearest.timeMs, startMs, timelineMs, visible, width)
                            if (abs(nearestX - offset.x) <= 16.dp.toPx()) {
                                onFlagTap(nearest)
                                return@detectTapGestures
                            }
                        }
                    }
                    if (offset.y >= size.height - 40.dp.toPx()) {
                        val nearest = notes.minByOrNull { abs(msAt(offset.x) - it.timeMs) }
                        if (nearest != null) {
                            val nearestX = xForMs(nearest.timeMs, startMs, timelineMs, visible, width)
                            if (abs(nearestX - offset.x) <= 16.dp.toPx()) {
                                onNoteTap(nearest)
                                return@detectTapGestures
                            }
                        }
                    }
                    viewport.resumeFollow()
                    onSeek(msAt(offset.x))
                }
            }
            .pointerInput(timelineMs) {
                detectTransformGestures { centroid, pan, zoomChange, _ ->
                    val width = size.width.toFloat().coerceAtLeast(1f)
                    if (abs(zoomChange - 1f) > 0.001f) {
                        viewport.setZoom(
                            value = viewport.zoom * zoomChange,
                            anchorFraction = centroid.x / width,
                        )
                    }
                    if (abs(pan.x) > 0.01f) {
                        viewport.panBy(pan.x / width / viewport.zoom)
                    }
                }
            }
    ) {
        val width = size.width
        val height = size.height
        if (width <= 0f || height <= 0f) return@Canvas

        val centerY = height / 2f
        val amplitude = height * 0.44f
        val visible = 1f / viewport.zoom
        val startMs = (viewport.startFraction * timelineMs).toLong()
        val musicColor = WaveCueColors.WaveformFill
        val playedColor = WaveCueColors.WaveformPlayed
        val playheadX = ((positionMs.toFloat() / timelineMs) - startFractionOf(viewport)) /
            visible * width

        // Background grid: whole-second ticks that stay legible at every zoom level.
        drawGrid(startMs, timelineMs, visible, width, height)

        pendingSilence.forEach { region ->
            drawSilence(region, startMs, timelineMs, visible, width, height, Color(0x33E8C47A))
        }

        if (waveform != null && waveform.binCount > 0 && waveform.sampleRate > 0) {
            val framesPerPixel =
                timelineMs.toDouble() * visible / 1000.0 * waveform.sampleRate / width
            val level = waveform.levelFor(framesPerPixel)
            val binFrames = level.framesPerBin.toDouble().coerceAtLeast(1.0)
            val startFrame = startMs * waveform.sampleRate / 1000.0
            val framesPerPixelF = framesPerPixel.coerceAtLeast(0.0001)
            val firstBin = (startFrame / binFrames).toInt().coerceAtLeast(0)
            val endFrame = startFrame + framesPerPixelF * width
            val lastBin = (endFrame / binFrames).toInt().coerceAtMost(level.binCount - 1)

            var previousX = -1f
            for (bin in firstBin..lastBin) {
                val binStart = bin * binFrames
                val x = ((binStart - startFrame) / framesPerPixelF).toFloat()
                if (x < -1f || x > width + 1f) continue
                if (x - previousX < 0f) continue
                previousX = x

                // Square-root (compressed) amplitude scale so 24-bit and 16-bit
                // files with different dynamic ranges render identically.
                val minV = kotlin.math.sign(level.min[bin].toFloat()) *
                    kotlin.math.sqrt(kotlin.math.abs(level.min[bin].toFloat()) / 32768f)
                val maxV = kotlin.math.sign(level.max[bin].toFloat()) *
                    kotlin.math.sqrt(kotlin.math.abs(level.max[bin].toFloat()) / 32768f)
                val rmsV = kotlin.math.sqrt(level.rms[bin].toFloat() / 32768f)
                val color = if (x <= playheadX) playedColor else musicColor

                drawLine(
                    color = color,
                    start = Offset(x, centerY - maxV * amplitude),
                    end = Offset(x, centerY - minV * amplitude),
                    strokeWidth = 1.1f,
                )
                drawLine(
                    color = if (x <= playheadX) WaveCueColors.PrimaryDim else WaveCueColors.WaveformRms,
                    start = Offset(x, centerY - rmsV * amplitude),
                    end = Offset(x, centerY + rmsV * amplitude),
                    strokeWidth = 1.1f,
                )
            }
        }

        // Center reference line.
        drawLine(
            color = WaveCueColors.Outline.copy(alpha = 0.55f),
            start = Offset(0f, centerY),
            end = Offset(width, centerY),
            strokeWidth = 1f,
        )

        flags.forEach { flag ->
            val x = xForMs(flag.timeMs, startMs, timelineMs, visible, width)
            if (x < -2f || x > width + 2f) return@forEach
            drawLine(
                color = WaveCueColors.Flag.copy(alpha = 0.85f),
                start = Offset(x, 0f),
                end = Offset(x, height),
                strokeWidth = 1.2f,
            )
            drawFlagMarker(x)
        }

        notes.forEach { note ->
            val x = xForMs(note.timeMs, startMs, timelineMs, visible, width)
            if (x < -4f || x > width + 4f) return@forEach
            drawCircle(
                color = WaveCueColors.Note,
                radius = 6f,
                center = Offset(x, height - 14.dp.toPx()),
            )
            drawLine(
                color = WaveCueColors.Note.copy(alpha = 0.35f),
                start = Offset(x, height - 8.dp.toPx()),
                end = Offset(x, height),
                strokeWidth = 1f,
            )
        }

        if (playheadX >= 0f && playheadX <= width) {
            drawLine(
                color = WaveCueColors.Playhead,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, height),
                strokeWidth = 2f,
            )
            drawCircle(
                color = WaveCueColors.Playhead,
                radius = 3.5f,
                center = Offset(playheadX, 4f),
            )
        }
    }
}

private fun startFractionOf(viewport: WaveformViewportState): Float = viewport.startFraction

private fun DrawScope.drawGrid(
    startMs: Long,
    timelineMs: Long,
    visible: Float,
    width: Float,
    height: Float,
) {
    val visibleMs = timelineMs * visible
    if (visibleMs <= 0.0) return
    val candidates = longArrayOf(100L, 250L, 500L, 1_000L, 2_000L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L)
    val targetLines = max(3f, width / 110f)
    val step = candidates.firstOrNull { visibleMs / it <= targetLines } ?: candidates.last()
    var tick = (startMs / step) * step
    if (tick < startMs) tick += step
    while (tick <= startMs + visibleMs) {
        val x = ((tick - startMs) / visibleMs * width).toFloat()
        drawLine(
            color = WaveCueColors.Outline.copy(alpha = 0.5f),
            start = Offset(x, 0f),
            end = Offset(x, height),
            strokeWidth = 1f,
        )
        tick += step
    }
}

private fun DrawScope.drawSilence(
    region: SilenceRegion,
    startMs: Long,
    timelineMs: Long,
    visible: Float,
    width: Float,
    height: Float,
    color: Color,
) {
    val visibleMs = timelineMs * visible
    if (visibleMs <= 0.0) return
    val x1 = ((region.startMs - startMs) / visibleMs * width).toFloat()
    val x2 = ((region.endMs - startMs) / visibleMs * width).toFloat()
    if (x2 < 0f || x1 > width) return
    val left = x1.coerceIn(0f, width)
    val right = x2.coerceIn(0f, width)
    if (right <= left) return
    drawRect(
        color = color,
        topLeft = Offset(left, 0f),
        size = Size(right - left, height),
    )
    drawRect(
        color = WaveCueColors.Secondary.copy(alpha = 0.6f),
        topLeft = Offset(left, 0f),
        size = Size(right - left, height),
        style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.5f),
    )
}

private fun DrawScope.drawFlagMarker(x: Float) {
    val size = 6.dp.toPx()
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(x - size, 0f)
        lineTo(x + size, 0f)
        lineTo(x, size * 1.6f)
        close()
    }
    drawPath(path, color = WaveCueColors.Flag)
}

private fun xForMs(
    ms: Long,
    startMs: Long,
    timelineMs: Long,
    visible: Float,
    width: Float,
): Float {
    val visibleMs = timelineMs * visible
    if (visibleMs <= 0.0) return 0f
    return ((ms - startMs) / visibleMs * width).toFloat()
}
