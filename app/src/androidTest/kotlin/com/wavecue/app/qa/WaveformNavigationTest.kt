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

        clickTag(TestTags.ZoomIn)
        waitUntil(5_000) { textOf(TestTags.ZoomLabel) == "2x" }
        clickTag(TestTags.ZoomIn)
        Thread.sleep(300)
        waitUntil(10_000) { textOf(TestTags.ZoomLabel) == "4x" }
        clickTag(TestTags.ZoomOut)
        waitUntil(5_000) { textOf(TestTags.ZoomLabel) == "2x" }

        // Panning at 8x moves the visible window later in the track.
        // Pause and seek back to the start so the pan has room to move later.
        clickTag(TestTags.TransportToggle)
        waitUntil(5_000) { !TestHarness.isPlaying(context) }
        repeat(8) { clickTag(TestTags.TransportBack5) }
        Thread.sleep(500)
        clickTag(TestTags.ZoomIn)
        waitUntil(10_000) { textOf(TestTags.ZoomLabel) == "4x" }
        clickTag(TestTags.ZoomIn)
        waitUntil(10_000) { textOf(TestTags.ZoomLabel) == "8x" }
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
        assertTrue("viewport did not pan: $startBefore -> $startAfter", startAfter > startBefore)

        clickTag(TestTags.ZoomFit)
        waitUntil(5_000) { textOf(TestTags.ZoomLabel) == "1x" }
        assertEquals(0L, parseTimecode(textOf(TestTags.ViewportStart)))

        // Tapping the middle of a 32 s track seeks to roughly 16 s.
        compose.onNodeWithTag(TestTags.PlayerWaveform).performTouchInput { click(center) }
        waitUntil(10_000) { positionMs() in 14_500..18_000 }
    }
}
