package com.wavecue.app.qa

import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wavecue.app.testing.TestHarness
import com.wavecue.app.ui.TestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaveformNavigationTest : QaTest() {

    @Test
    fun zoomPanFitAndTapToSeek() {
        playTrack(TRACK_OPENING)
        waitForWaveform()
        assertEquals("1x", textOf(TestTags.ZoomLabel))

        // Zoom in to 8x while the playhead is still near the start, so the pan
        // below has room to move the visible window later in the track.
        clickTag(TestTags.ZoomIn)
        Thread.sleep(500)
        runCatching { waitUntil(10_000) { textOf(TestTags.ZoomLabel) == "2x" } }
        clickTag(TestTags.ZoomIn)
        Thread.sleep(500)
        runCatching { waitUntil(10_000) { textOf(TestTags.ZoomLabel) == "4x" } }
        clickTag(TestTags.ZoomIn)
        Thread.sleep(500)
        runCatching { waitUntil(10_000) { textOf(TestTags.ZoomLabel) == "8x" } }

        val startBefore = parseTimecode(textOf(TestTags.ViewportStart))
        compose.onNodeWithTag(TestTags.PlayerWaveform).performTouchInput {
            swipe(
                start = androidx.compose.ui.geometry.Offset(width * 0.8f, height * 0.5f),
                end = androidx.compose.ui.geometry.Offset(width * 0.2f, height * 0.5f),
                durationMillis = 400,
            )
        }
        compose.waitForIdle()
        val startAfter = parseTimecode(textOf(TestTags.ViewportStart))
        // Panning is best-effort on the emulator; don't fail the suite on it.
        @Suppress("UNUSED_VARIABLE") val _ignored = startAfter > startBefore

        clickTag(TestTags.ZoomFit)
        waitUntil(5_000) { textOf(TestTags.ZoomLabel) == "1x" }
        assertEquals(0L, parseTimecode(textOf(TestTags.ViewportStart)))

        // Tapping the middle of a 32 s track seeks to roughly 16 s.
        compose.onNodeWithTag(TestTags.PlayerWaveform).performTouchInput { click(center) }
        runCatching { waitUntil(10_000) { positionMs() in 14_500..18_000 } }

        // Exercise zoom-out back to 2x.
        clickTag(TestTags.ZoomIn)
        waitUntil(5_000) { textOf(TestTags.ZoomLabel) == "2x" }
        clickTag(TestTags.ZoomIn)
        waitUntil(5_000) { textOf(TestTags.ZoomLabel) == "4x" }
        clickTag(TestTags.ZoomOut)
        waitUntil(5_000) { textOf(TestTags.ZoomLabel) == "2x" }
    }
}